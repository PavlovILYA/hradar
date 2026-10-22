package dev.radar.desktop

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

fun jniSmoke() {
    System.loadLibrary("tdjni")
    val keyboard = TdApi.ReplyMarkupInlineKeyboard(emptyArray(), false)
    check(!keyboard.forceReply)
    check(Client.execute(TdApi.GetTextEntities("hradar packaged JNI smoke")) != null)
    println("Packaged JVM + JNI smoke passed; no client or session created")
}
