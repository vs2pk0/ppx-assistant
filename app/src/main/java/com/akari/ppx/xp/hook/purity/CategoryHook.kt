@file:Suppress("unused")

package com.akari.ppx.xp.hook.purity

import com.akari.ppx.data.Const.CHANNEL_KEY
import com.akari.ppx.data.Const.MERGED_MEDIA_CHANNEL
import com.akari.ppx.data.XPrefs
import com.akari.ppx.data.isLegacyMediaChannel
import com.akari.ppx.data.readChannelPreferences
import com.akari.ppx.data.selectHomeChannels
import com.akari.ppx.utils.callMethodOrNullAs
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.BaseHook

class CategoryHook : BaseHook {
    override fun onHook() {
        val defaultChannel = XPrefs<String>("default_channel").toIntOrNull() ?: 1
        val preferences = if (XPrefs<Boolean>("modify_channels")) {
            readChannelPreferences(XPrefs<String>(CHANNEL_KEY))
        } else null

        // Filter only the displayed top-level list. The repository must keep the full
        // catalog for its disk cache, subchannels and channel ID lookups.
        "com.sup.superb.feedui.view.tabv2.FeedTabFragmentV2".hookBeforeMethod(
            cl,
            "a",
            List::class.java,
            Int::class.java,
            Int::class.javaObjectType,
            Boolean::class.java
        ) { param ->
            val available = (param.args[0] as? List<*>)?.filterNotNull().orEmpty()
            if (available.isEmpty()) return@hookBeforeMethod
            val visible = preferences?.let {
                selectHomeChannels(available, it, ::channelId) { channel ->
                    channel.callMethodOrNullAs<String>("getListName")
                }
            } ?: available
            val originalPage = available.getOrNull(param.args[1] as Int)
            param.args[0] = visible
            param.args[1] = if (originalPage == null) -1 else visible.indexOf(originalPage)
            val hostDefault = param.args[2] as? Int
            val visibleIds = visible.map(::channelId)
            val legacyDefault = MERGED_MEDIA_CHANNEL.takeIf { isLegacyMediaChannel(defaultChannel) }
            param.args[2] = listOfNotNull(defaultChannel, legacyDefault, hostDefault, 1)
                .firstOrNull { it in visibleIds } ?: visibleIds.first()
        }
    }

    private fun channelId(channel: Any): Int? = channel.callMethodOrNullAs("getPrimaryListId")
}
