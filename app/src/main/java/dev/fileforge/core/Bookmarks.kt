package dev.fileforge.core

import android.content.Context
import org.json.JSONArray

/** 目录书签：SharedPreferences 持久化的有序路径列表。 */
object Bookmarks {

    private const val PREF = "fileforge_bookmarks"
    private const val KEY = "paths"

    fun load(context: Context): List<String> {
        val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getString(i) }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, paths: List<String>) {
        val arr = JSONArray()
        paths.forEach { arr.put(it) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
