package com.droiddeck.launcher.input

import android.content.Context
import android.util.Log

object GameInputProfiles {
    private const val APPLIED = "applied-by-game"

    private fun prefs(context: Context) = context.getSharedPreferences("game-input-profiles", Context.MODE_PRIVATE)

    fun get(context: Context, scope: String): String? = prefs(context).getString("game.$scope", null)

    fun set(context: Context, scope: String, id: String?) {
        prefs(context).edit().apply {
            if (id == null || id == InputProfiles.AUTO) remove("game.$scope") else putString("game.$scope", id)
        }.apply()
    }

    fun applyFor(context: Context, scope: String?) {
        runCatching {
            val p = prefs(context)
            val library = InputLibraries.read(context)
            val current = library.selection(false)
            val wanted = scope?.let { get(context, it) }?.takeIf { id -> library.profiles.any { it.id == id } }
            // A manual pick in quick settings is kept; only a profile a game switched to is reverted to Auto.
            val target = wanted ?: if (current.profile == p.getString(APPLIED, null)) InputProfiles.AUTO else return
            if (current.profile != target || current.parts.isNotEmpty()) InputLibraries.update(context) { it.selectProfile(false, target) }
            p.edit().apply { if (wanted != null) putString(APPLIED, wanted) else remove(APPLIED) }.apply()
        }.onFailure { Log.w("GameInputProfiles", "Could not apply the game's controller profile", it) }
    }
}
