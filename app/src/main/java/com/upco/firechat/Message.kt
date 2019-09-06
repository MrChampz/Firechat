package com.upco.firechat

data class Message(
    private var _id: String? = null,
    val text: String? = null,
    val name: String? = null,
    val photoUrl: String? = null,
    val imageUrl: String? = null
) {
    val id
        get() = _id

    fun setId(id: String) {
        _id = id
    }
}