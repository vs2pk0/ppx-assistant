@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.BaseHook

class InfoHook : BaseHook {
    override fun onHook() {
        val (isCustomize, isModifyMsgCounts, isEnterBlackHouse) = listOf<Boolean>(
            XPrefs("customize"),
            XPrefs("modify_message_counts"),
            XPrefs("enter_black_house")
        )
        var (certifyType, certifyDesc, username, description, likeCount, followersCount, followingCount, point) = listOf<String>(
            XPrefs("certify_type"),
            XPrefs("certify_desc"),
            XPrefs("username"),
            XPrefs("description"),
            XPrefs("like_count"),
            XPrefs("followers_count"),
            XPrefs("following_count"),
            XPrefs("point")
        )
        val avatarViewClass = "com.sup.android.uikit.avatar.FrameAvatarView".findClass(cl)
        val method = avatarViewClass.declaredMethods.find { m ->
            m.parameterTypes.size == 2 && m.parameterTypes[0].name == "com.sup.android.mi.usercenter.model.UserInfo"
                    && m.parameterTypes[1] == Int::class.java
        }?.name
        avatarViewClass.hookBeforeMethod(
            method,
            "com.sup.android.mi.usercenter.model.UserInfo",
            Int::class.java
        ) { param ->
            val userInfo = param.args[0] ?: return@hookBeforeMethod
            val certifyInfoClass =
                "com.sup.android.mi.usercenter.model.UserInfo\$CertifyInfo".findClass(cl)
            isCustomize.check(true) {
                "com.sup.android.module.usercenter.UserCenterService".findClass(cl)
                    .callStaticMethod("getInstance")
                    ?.callMethod("getMyUserInfo")?.getLongFieldOrNull("id")
                    .takeIf { it != null && it > 0 }
                    .check(userInfo.getLongField("id")) {
                        if (isEnterBlackHouse) {
                            userInfo.setObjectField("punishmentList", arrayListOf<Any>().apply {
                                "com.sup.android.mi.usercenter.model.UserInfo\$Punishment".findClass(
                                    cl
                                ).new().apply {
                                    setObjectField("shortDesc", "已被移送小黑屋")
                                }.let(::add)
                            })
                        }
                        certifyType.checkUnless("") {
                            toInt().checkIf({ this in 1..3 }) {
                                certifyInfoClass.new().apply {
                                    callMethod("setCertifyType", this@checkIf)
                                    if (certifyDesc.isEmpty())
                                        certifyDesc = "阿勒，忘填描述了？"
                                    callMethod("setDescription", certifyDesc)
                                }.let { userInfo.setObjectField("certifyInfo", it) }
                            }
                        }
                        username.checkIf({ isNotEmpty() }) {
                            userInfo.setObjectField("name", this)
                        }
                        description.checkIf({ isNotEmpty() }) {
                            userInfo.setObjectField("description", this)
                        }
                        with(userInfo) {
                            runCatching { likeCount.toLong() }.getOrNull()
                                ?.let { setLongField("likeCount", it) }
                            runCatching { followersCount.toLong() }.getOrNull()
                                ?.let { setLongField("followersCount", it) }
                            runCatching { followingCount.toLong() }.getOrNull()
                                ?.let { setLongField("followingCount", it) }
                            runCatching { point.toLong() }.getOrNull()
                                ?.let { setLongField("point", it) }
                        }
                    }
            }
        }
        "com.google.gson.Gson".findClass(cl)
            .hookAfterMethod("fromJson", String::class.java, Class::class.java) { param ->
                if (isModifyMsgCounts && (param.args[1] as Class<*>).name.startsWith("com.sup.android.m_message.data.")) {
                    param.result.getObjectFieldOrNull("data")?.getObjectFieldOrNullAs<List<*>>("count_map")
                        ?.forEach {
                            it.setLongField("count", 100L)
                        }
                }
            }
    }
}
