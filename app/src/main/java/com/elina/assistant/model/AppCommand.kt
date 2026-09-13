package com.elina.assistant.model

enum class AppCommand { OPEN_APP, CLOSE_CURRENT_APP, GO_BACK, GO_HOME, CLICK_TEXT, TYPE_TEXT, SCROLL_UP, SCROLL_DOWN, VOLUME_UP, VOLUME_DOWN, MEDIA_PLAY, MEDIA_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS, OPEN_SETTINGS, STORE_MEMORY, FORGET_MEMORY }
data class CommandArgs(val values: Map<String, String> = emptyMap())
