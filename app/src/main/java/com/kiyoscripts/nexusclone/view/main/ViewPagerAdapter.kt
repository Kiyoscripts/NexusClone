package com.kiyoscripts.nexusclone.view.main

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.kiyoscripts.nexusclone.view.apps.AppsFragment

/**
 * Pager over virtual users.
 *
 * Two things here are load-bearing and were both wrong before.
 *
 * 1. Stable item ids. [FragmentStateAdapter] keys its fragment cache on
 *    [getItemId], which defaults to the adapter position. When the user list is
 *    rebuilt the count changes, and with position-based ids every existing
 *    fragment is considered a different item: the adapter tears down and
 *    recreates all of them, and ViewPager2's scroll state is left pointing at a
 *    position that no longer means what it did. Overriding [getItemId] with the
 *    virtual user id and [containsItem] to match makes the rebuild a no-op for
 *    fragments that are still present, which is what makes
 *    [notifyDataSetChanged] safe here.
 *
 * 2. No phantom page. The previous version appended one extra page at
 *    `userList.size` as an "add user" entry. Nothing ever rendered an add-user
 *    UI on it, and its `userID` was a virtual user id that does not exist, so
 *    selecting it set `currentUser` to a bogus value and every subsequent
 *    install or launch targeted a user the engine had never created. The page
 *    is gone; the list is exactly the real users.
 */
class ViewPagerAdapter(appCompatActivity: AppCompatActivity) : FragmentStateAdapter(appCompatActivity) {

    private val userIds = mutableListOf<Int>()

    /**
     * Replace the page set.
     *
     * Callers pass the real virtual user ids in display order. The list is copied
     * so a caller that keeps mutating its own list cannot desynchronise the
     * adapter from what it last rendered.
     */
    fun submit(newUserIds: List<Int>) {
        userIds.clear()
        userIds.addAll(newUserIds)
        notifyDataSetChanged()
    }

    /** The virtual user id currently shown at [position], or null if out of range. */
    fun userIdAt(position: Int): Int? = userIds.getOrNull(position)

    fun indexOfUser(userId: Int): Int = userIds.indexOf(userId)

    override fun getItemCount(): Int = userIds.size

    override fun getItemId(position: Int): Long = userIds[position].toLong()

    override fun containsItem(itemId: Long): Boolean = userIds.contains(itemId.toInt())

    override fun createFragment(position: Int): Fragment =
        AppsFragment.newInstance(userIds[position])
}
