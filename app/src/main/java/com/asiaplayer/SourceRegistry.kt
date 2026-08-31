package com.asiaplayer

import android.content.Context

/** Runtime source definitions used by the Android app. Domains are mirrors and
 * are intentionally health-checked before being offered to the user. */
data class AppSource(
    val id: String,
    val label: String,
    val hosts: List<String>,
    val family: Family
) {
    enum class Family { KISSKH, MYASIAN_TV }
}

object SourceRegistry {
    private const val PREFS = "sources"
    private const val HOST = "kisskh_host"
    val all: List<AppSource> = listOf(
        AppSource("kisskh", "KissKH", listOf("kisskh.is", "kisskh.nl", "kisskh.do", "kisskh.co", "kisskh.cl", "kisskh.xyz", "kisskh.asia", "kisskh.li", "kisskh.biz", "kisskh.lv", "kisskh.club", "kisskh.ca", "kisskh.pro", "kisskh9.se"), AppSource.Family.KISSKH),
        AppSource("myasiantv", "MyAsianTV", listOf("myasiantv.es", "myasiantv.ac", "myasiantv.cc", "myasiantv.cx", "myasiantv.io", "myasiantv.pe", "myasiantv.tv", "myasiantv.ru", "myasiantv.se", "myasiantv.com.lv", "myasiantv.com.ro", "myasiantv9.com.ro", "myasiantv.biz.tr"), AppSource.Family.MYASIAN_TV)
    )

    fun kissKhApi(host: String, path: String): String = "https://$host$path"
    fun host(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HOST, all[0].hosts[0]) ?: all[0].hosts[0]
    fun setHost(context: Context, host: String) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(HOST, host).apply() }
}
