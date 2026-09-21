/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.home

import androidx.compose.runtime.Composable
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class HomeAppSortMode(val labelRes: StringResource, val descriptionRes: StringResource) {
    RECOMMENDED(Res.string.home_sort_recommended, Res.string.home_sort_recommended_desc),
    NAME_ASC(Res.string.home_sort_name_asc, Res.string.home_sort_name_asc_desc),
    NAME_DESC(Res.string.home_sort_name_desc, Res.string.home_sort_name_desc_desc),
    UPDATES_FIRST(Res.string.home_sort_updates_first, Res.string.home_sort_updates_first_desc),
    RECENTLY_PATCHED(Res.string.home_sort_recently_patched, Res.string.home_sort_recently_patched_desc);

    val label: String
        @Composable
        get() = stringResource(labelRes)

    val description: String
        @Composable
        get() = stringResource(descriptionRes)

    companion object {
        fun fromPreference(value: String?): HomeAppSortMode =
            entries.firstOrNull { it.name == value } ?: RECOMMENDED
    }
}

data class HomeSortKeys(
    val displayName: String,
    val packageName: String,
    val isPatched: Boolean,
    val isInstalled: Boolean,
    val hasPatchUpdate: Boolean,
    val patchedAt: Long,
)

/**
 * The keys every sort mode orders by, read off the one semantic state behind the
 * card rather than off three maps the caller has to keep in step.
 */
fun HomeAppItem.sortKeys(): HomeSortKeys = HomeSortKeys(
    displayName = displayName,
    packageName = packageName,
    isPatched = isTracked,
    isInstalled = isOnDevice,
    hasPatchUpdate = showsRebuildBadge,
    patchedAt = record?.patchedAt ?: Long.MIN_VALUE,
)

private val recommended: Comparator<HomeSortKeys> =
    compareByDescending<HomeSortKeys> { it.isPatched }
        .thenByDescending { it.isInstalled }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName }

fun HomeAppSortMode.comparator(): Comparator<HomeSortKeys> = when (this) {
    HomeAppSortMode.RECOMMENDED -> recommended
    HomeAppSortMode.NAME_ASC ->
        compareBy<HomeSortKeys, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName }
    HomeAppSortMode.NAME_DESC ->
        compareBy<HomeSortKeys, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName }
            .reversed()
    HomeAppSortMode.UPDATES_FIRST ->
        compareByDescending<HomeSortKeys> { it.hasPatchUpdate }.then(recommended)
    HomeAppSortMode.RECENTLY_PATCHED ->
        compareByDescending<HomeSortKeys> { it.patchedAt }.then(recommended)
}
