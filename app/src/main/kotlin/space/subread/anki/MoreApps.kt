package space.subread.anki

import androidx.annotation.StringRes

/** One link of the "More apps" section: a name, one line about it, and the page that a tap opens. */
class MoreApp(@param:StringRes val name: Int, @param:StringRes val line: Int, val url: String)

/**
 * The other sites and apps of the same author, in the order of the catalog: the sites,
 * then the Android apps, then the list of all projects. SubRead Anki is not in it.
 */
val MORE_APPS = listOf(
    MoreApp(R.string.more_subread, R.string.more_subread_line, "https://subread.space/"),
    MoreApp(R.string.more_book_simulator, R.string.more_book_simulator_line, "https://booksimulator.com/"),
    MoreApp(R.string.more_honjimaku, R.string.more_honjimaku_line, "https://honjimaku.com/"),
    MoreApp(R.string.more_sbm_sync, R.string.more_sbm_sync_line, "https://sbmsync.com/"),
    MoreApp(R.string.more_subread_android, R.string.more_subread_android_line,
        "https://github.com/equwal/subread-android/releases/latest"),
    MoreApp(R.string.more_subread_overlay, R.string.more_subread_overlay_line,
        "https://github.com/equwal/subread-overlay/releases/latest"),
    MoreApp(R.string.more_subread_dictionary, R.string.more_subread_dictionary_line,
        "https://github.com/equwal/subread-dictionary/releases/latest"),
    MoreApp(R.string.more_subrep, R.string.more_subrep_line,
        "https://github.com/equwal/subrep-android/releases/latest"),
    MoreApp(R.string.more_sbm_android, R.string.more_sbm_android_line,
        "https://github.com/equwal/sbm-android/releases/latest"),
    MoreApp(R.string.more_rebind, R.string.more_rebind_line, "https://github.com/equwal/rebind/releases"),
    MoreApp(R.string.more_ink_recents, R.string.more_ink_recents_line,
        "https://github.com/equwal/ink-recents/releases/latest"),
    MoreApp(R.string.more_ink_dim, R.string.more_ink_dim_line, "https://github.com/equwal/ink-dim/releases/latest"),
    MoreApp(R.string.more_ink_update, R.string.more_ink_update_line,
        "https://github.com/equwal/ink-update/releases/latest"),
    MoreApp(R.string.more_all_projects, R.string.more_all_projects_line, "https://recentlywritten.com/projects.html"),
)
