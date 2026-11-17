package com.akari.ppx.xp.hook.misc

import com.akari.ppx.xp.hook.SwitchHook

class WelfareEntryHook : SwitchHook("remove_welfare_entry") {
    override fun onHook() = hideBottomTabs("task", "福利", "福利中心")
}
