package com.upco.firechat

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.firebase.ui.database.FirebaseRecyclerAdapter
import com.firebase.ui.database.FirebaseRecyclerOptions
import com.firebase.ui.database.SnapshotParser
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.item_message.view.*

class MainActivity: AppCompatActivity(), GoogleApiClient.OnConnectionFailedListener {

    class MessageViewHolder(v: View): RecyclerView.ViewHolder(v) {
        val tvMessage   = v.tv_message
        val ivMessage   = v.iv_message
        val tvMessenger = v.tv_messenger
        val ivMessenger = v.iv_messenger
    }

    private var username: String? = null
    private var photoUrl: String? = null
    private lateinit var preferences: SharedPreferences
    private lateinit var googleApiClient: GoogleApiClient

    // Firebase instance variables
    private var firebaseUser: FirebaseUser? = null
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var firebaseDatabaseRef: DatabaseReference
    private lateinit var firebaseAdapter: FirebaseRecyclerAdapter<Message, MessageViewHolder>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get default shared preferences
        preferences = PreferenceManager.getDefaultSharedPreferences(this)

        // Set default username is anonymous
        username = ANONYMOUS

        // Initialize FirebaseAuth
        firebaseAuth = FirebaseAuth.getInstance()
        firebaseUser = firebaseAuth.currentUser

        if (firebaseUser == null) {
            // Not signed in, launch the SignInActivity
            startActivity(Intent(this, SignInActivity::class.java))
            finish()
        } else {
            username = firebaseUser?.displayName!!
            if (firebaseUser?.photoUrl != null) {
                photoUrl = firebaseUser?.photoUrl.toString()
            }
        }

        // Setup google auth api
        googleApiClient = GoogleApiClient.Builder(this)
            .enableAutoManage(this, this)
            .addApi(Auth.GOOGLE_SIGN_IN_API)
            .build()

        /*
         * Setup views
         */

        rv_messages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }

        et_message.addTextChangedListener(object: TextWatcher {
            override fun onTextChanged(charSeq: CharSequence?, p1: Int, p2: Int, p3: Int) {
                // If has a message, enable the send button
                btn_send.isEnabled = charSeq.toString().trim().isNotEmpty()
            }

            override fun beforeTextChanged(charSeq: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(editable: Editable?) {}
        })

        btn_send.setOnClickListener {
            val message = Message(
                null,
                et_message.text.toString(),
                username,
                photoUrl,
                null /* no image */
            )

            firebaseDatabaseRef.child(MESSAGES_CHILD)
                    .push()
                    .setValue(message)

            et_message.setText("")
        }

        iv_add_message.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
            startActivityForResult(intent, REQUEST_IMAGE)
        }

        // New child entries
        firebaseDatabaseRef = FirebaseDatabase.getInstance().reference
        val parser = SnapshotParser { snapshot ->
            val message = snapshot.getValue(Message::class.java)!!
            message.setId(snapshot.key!!)
            message
        }

        val messagesRef = firebaseDatabaseRef.child(MESSAGES_CHILD)
        val options = FirebaseRecyclerOptions.Builder<Message>()
            .setQuery(messagesRef, parser)
            .build()

        firebaseAdapter = object: FirebaseRecyclerAdapter<Message, MessageViewHolder>(options) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
                val inflater = LayoutInflater.from(parent.context)
                return MessageViewHolder(inflater.inflate(R.layout.item_message, parent, false))
            }

            override fun onBindViewHolder(holder: MessageViewHolder, position: Int, message: Message) {
                pb_loading.visibility = ProgressBar.INVISIBLE

                if (message.text != null) {
                    holder.tvMessage.text = message.text
                    holder.tvMessage.visibility = TextView.VISIBLE
                    holder.ivMessage.visibility = ImageView.GONE
                } else if (message.imageUrl != null) {
                    val imageUrl = message.imageUrl
                    if (imageUrl.startsWith("gs://")) {
                        val storageRef = FirebaseStorage.getInstance()
                            .getReferenceFromUrl(imageUrl)
                        storageRef.downloadUrl.addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                val downloadUrl = task.result.toString()
                                Glide.with(holder.ivMessage.context)
                                     .load(downloadUrl)
                                     .into(holder.ivMessage)
                            } else {
                                Log.w(TAG, "Getting download url was not successful.", task.exception)
                            }
                        }
                    } else {
                        Glide.with(holder.ivMessage.context)
                             .load(message.imageUrl)
                             .into(holder.ivMessage)
                    }
                    holder.ivMessage.visibility = ImageView.VISIBLE
                    holder.tvMessage.visibility = ImageView.GONE
                }

                holder.tvMessenger.text = message.name
                if (message.photoUrl == null) {
                    holder.ivMessenger.setImageDrawable(ContextCompat.getDrawable(
                        this@MainActivity,
                        R.drawable.ic_account_circle_black_24dp
                    ))
                } else {
                    Glide.with(this@MainActivity)
                         .load(message.photoUrl)
                         .into(holder.ivMessenger)
                }
            }
        }

        firebaseAdapter.registerAdapterDataObserver(object: RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)

                val llManager = rv_messages.layoutManager as LinearLayoutManager
                val messageCount = firebaseAdapter.itemCount
                val lastVisiblePosition = llManager.findLastCompletelyVisibleItemPosition()

                // If the recycler view is initially being loaded or the user is at the bottom
                // of the list, scroll to the bottom of the list to show the newly added message.
                if (lastVisiblePosition == -1 ||
                    (positionStart >= (messageCount - 1) && lastVisiblePosition == (positionStart - 1))) {
                    rv_messages.scrollToPosition(positionStart)
                }
            }
        })

        rv_messages.adapter = firebaseAdapter
    }

    override fun onStart() {
        super.onStart()
        // Check if user is signed in
        // TODO: Add code to check if user is signed in
    }

    override fun onPause() {
        firebaseAdapter.stopListening()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        firebaseAdapter.startListening()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_sign_out -> {
                firebaseAuth.signOut()
                Auth.GoogleSignInApi.signOut(googleApiClient)
                username = ANONYMOUS
                startActivity(Intent(this, SignInActivity::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode = $requestCode, resultCode = $resultCode")

        if (requestCode == REQUEST_IMAGE) {
            if (resultCode == RESULT_OK) {
                if (data != null) {
                    val uri = data.data
                    Log.d(TAG, "Uri: ${uri.toString()}")

                    val tempMessage = Message(
                        null,
                        null,
                        username,
                        photoUrl,
                        LOADING_IMAGE_URL
                    )

                    firebaseDatabaseRef.child(MESSAGES_CHILD)
                            .push()
                            .setValue(tempMessage) { error, ref ->
                                if (error == null) {
                                    val storageRef = FirebaseStorage.getInstance()
                                            .getReference(firebaseUser!!.uid)
                                            .child(ref.key!!)
                                            .child(uri?.lastPathSegment!!)

                                    putImageInStorage(storageRef, uri, ref.key!!)
                                } else {
                                    Log.w(
                                        TAG,
                                        "Não é possível salvar a mensagem no banco de dados.",
                                        error.toException()
                                    )
                                }
                            }
                }
            }
        }
    }

    override fun onConnectionFailed(connectionResult: ConnectionResult) {
        // An unresolvable error has occurred and Google APIs (including Sign-In) will not
        // be available.
        Log.d(TAG, "onConnectionFailed: $connectionResult")
        Toast.makeText(this, "Google Play Services error.", Toast.LENGTH_SHORT).show()
    }

    private fun putImageInStorage(storageRef: StorageReference, uri: Uri, key: String) {
        storageRef.putFile(uri).addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                task.result?.metadata?.reference?.downloadUrl?.addOnCompleteListener {
                    if (it.isSuccessful) {
                        val message = Message(
                            null,
                            null,
                            username,
                            photoUrl,
                            it.result.toString()
                        )

                        firebaseDatabaseRef.child(MESSAGES_CHILD).child(key)
                                .setValue(message)
                    }
                }
            } else {
                Log.w(TAG, "Erro ao fazer upload da imagem.", task.exception)
            }
        }
    }

    companion object {
        const val MESSAGES_CHILD = "messages"
        const val DEFAULT_MSG_LENGTH_LIMIT = 10
        const val ANONYMOUS = "anonymous"

        private const val REQUEST_INVITE = 1
        private const val REQUEST_IMAGE = 2
        private const val LOADING_IMAGE_URL = "https://www.google.com/images/spin-32.gif"
        private const val MESSAGE_SENT_EVENT = "message_sent"
        private const val MESSAGE_URL = ""

        private val TAG = MainActivity::class.java.simpleName
    }
}
