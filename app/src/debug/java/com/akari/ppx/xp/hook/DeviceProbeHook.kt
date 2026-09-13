package com.akari.ppx.xp.hook

import android.os.Handler
import android.os.Looper
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init

/** Runs only in debug builds, explicitly enabled by the signed test APK. */
class DeviceProbeHook : BaseHook {
    private var ran = false

    override fun onHook() {
        var appearanceRan = false
        Init.mainActivityClass?.hookAfterMethod("onResume") { param ->
            if (!appearanceRan && XPrefs<Boolean>("__probe_appearance")) {
                appearanceRan = true
                val activity = param.thisObject as android.app.Activity
                probe("splash_forced_ad_uses_no_ad_path") {
                    val helperClass = Init.mainActivityClass!!.getDeclaredField("mSplashHelper").type
                    val method = helperClass.declaredMethods.single {
                        it.parameterTypes.size == 3 && it.parameterTypes[2] == Boolean::class.javaPrimitiveType
                    }
                    val helper = helperClass.new()
                    val isolatedActivity = method.parameterTypes[0].new()
                    method.invoke(helper, isolatedActivity, 0, true)
                    !helper.getBooleanField("f")
                }
                probe("splash_main_not_showing") {
                    !activity.getObjectField("mSplashHelper").getBooleanField("e")
                }
                probe("splash_image_stays_hidden") {
                    activity.callMethod("updateFakedSplashViewVisible", true)
                    activity.getObjectFieldOrNullAs<android.view.View>("mFakedSplashView")?.visibility != android.view.View.VISIBLE
                }
                probe("female_custom_color_and_recycled_name") { checkFemaleColor() }
            }
        }
        Init.mainActivityClass?.hookAfterMethod("onResume") {
            if (!ran && XPrefs<Boolean>("__probe_models")) {
                ran = true
                Handler(Looper.getMainLooper()).postDelayed({ runProbes() }, 1000)
            }
        }
    }

    private fun model(name: String) = name.findClass(Init.cl).new()

    private fun probe(name: String, test: () -> Boolean) {
        runCatching { check(test()) { "unexpected result" } }
            .onSuccess { Log.i("DEVICE_TEST PASS $name") }
            .onFailure { Log.e("DEVICE_TEST FAIL $name: ${it.stackTraceToString()}") }
    }

    private fun runProbes() {
        probe("comment_color_publish_sanitizer") {
            val type = "com.sup.android.module.publish.view.m".findClass(Init.cl)
            val result = type.callStaticMethod("e", "PPX-COLOR-UNIT")
            result == CommentColors.format("PPX-COLOR-UNIT", XPrefs("comment_text_color", "none"))
        }
        probe("red_color_host_renderer") {
            val renderer = "com.sup.android.utils.cd".findClass(Init.cl).getStaticObjectField("b")
            val data = "com.sup.android.utils.ce".findClass(Init.cl).new(Init.ctx)
                .setObjectField("h", CommentColors.format("PPX-RED", "red")).setBooleanField("g", false)
            val rich = renderer.callMethod("a", data) as android.text.Spanned
            rich.toString() == "PPX-RED" && rich.getSpans(0, rich.length, android.text.style.ForegroundColorSpan::class.java)
                .any { it.foregroundColor == android.graphics.Color.parseColor("#D93025") }
        }
        probe("save_action_label") {
            val view = android.widget.LinearLayout(Init.ctx)
            val text = android.widget.TextView(Init.ctx).apply { this.text = "保存视频" }
            view.addView(text)
            val action = "com.sup.android.i_sharecontroller.model.OptionAction\$OptionActionType".findClass(Init.cl)
                .enumConstants.first { (it as Enum<*>).name == "ACTION_SAVE" }
            view.tag = action
            text.text.toString() == "去水印保存"
        }
        probe("image_save_action_label") {
            val view = android.widget.LinearLayout(Init.ctx)
            val text = android.widget.TextView(Init.ctx).apply { this.text = "保存图片" }
            view.addView(text)
            val action = "com.sup.android.i_sharecontroller.model.OptionAction\$OptionActionType".findClass(Init.cl)
                .enumConstants.first { (it as Enum<*>).name == "ACTION_SAVE" }
            view.tag = action
            text.text.toString() == "去水印保存"
        }
        probe("save_label_does_not_change_unrelated_text") {
            val text = android.widget.TextView(Init.ctx).apply { this.text = "保存视频" }
            text.text.toString() == "保存视频"
        }
        probe("download_restriction_comment") {
            model("com.sup.android.mi.feed.repo.bean.comment.Comment").callMethod("getCanDownload") == true
        }
        probe("download_restriction_video") {
            model("com.sup.android.mi.feed.repo.bean.cell.VideoFeedItem").callMethod("isCanDownload") == true
        }
        probe("advanced_danmaku") {
            val user = model("com.sup.android.mi.usercenter.model.UserInfo")
            user.setObjectField("privilege", model("com.sup.android.mi.usercenter.model.UserInfo\$UserPrivilege"))
            user.callMethod("getUserPrivilege").getBooleanField("canSendAdvanceDanmaku")
        }
        probe("advanced_danmaku_null_privilege") {
            model("com.sup.android.mi.usercenter.model.UserInfo").callMethod("getUserPrivilege")
                .getBooleanField("canSendAdvanceDanmaku")
        }
        probe("danmaku_sender_id_fallback") {
            com.akari.ppx.xp.hook.assist.DanmakuHook().extractUserId(SenderFixture()) == 42L
        }
        probe("registration_time") {
            val user = model("com.sup.android.mi.usercenter.model.UserInfo").setLongField("createTime", 1700000000L)
            val achievements = user.callMethod("getAchievements") as List<*>
            achievements.any { it.callMethodAs<String>("getDescription").startsWith("注册:") }
        }
        probe("registration_no_duplicates") {
            val user = model("com.sup.android.mi.usercenter.model.UserInfo").setLongField("createTime", 1700000000L)
            user.setObjectField("achievements", arrayListOf<Any>())
            user.callMethod("getAchievements")
            (user.callMethod("getAchievements") as List<*>).size == 1
        }
        probe("avatar_decoration_empty") {
            val user = model("com.sup.android.mi.usercenter.model.UserInfo")
            val decoration = model("com.sup.android.mi.usercenter.model.UserInfo\$Decoration")
            decoration.setObjectField("decorationInfos", arrayListOf<Any>())
            user.setObjectField("decorationList", arrayListOf(decoration))
            (user.callMethod("getDecorationList") as List<*>).size == 1
        }
        probe("story_list") {
            model("com.sup.android.mi.feed.repo.bean.cell.StoryInfo").callMethod("getStoryList") == null
        }
        probe("comment_exact_time") {
            val clazz = Init.inexactDateClass!!
            val method = clazz.declaredMethods.first { it.name == Init.inexactDate() && it.parameterTypes.size == 2 }
            method.isAccessible = true
            val owner = if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else singleton(clazz)
            val value = method.invoke(owner, 1700000000L, null) as String
            value.contains("2023") && value.contains(":")
        }
        probe("video_4k_gate") {
            val clazz = "com.sup.android.m_chooser.impl.c".findClass(Init.cl)
            val method = clazz.getDeclaredMethod("b", Int::class.java, Int::class.java).apply { isAccessible = true }
            method.invoke(if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else singleton(clazz), 3840, 2160) == true
        }
        probe("default_channel") {
            model("com.sup.superb.feedui.bean.CategoryListModel").callMethod("getDefaultChannel") ==
                XPrefs<String>("default_channel", "1").toInt()
        }
        probe("share_after_download") {
            Init.videoDownloadConfigClass!!.new().callMethod("n") == false
        }
        probe("highlight_setting") { setting("bds_enable_highlight", false) == true }
        probe("god_icon_setting") { setting("common_god_icon_style", 1) == 0 }
        probe("interaction_digg") {
            model("com.sup.android.mi.feed.repo.bean.cell.AbsFeedItem\$ItemRelation").callMethod("getDiggType") ==
                XPrefs<String>("digg_style", "10").toInt()
        }
        probe("interaction_diss") {
            model("com.sup.android.mi.feed.repo.bean.cell.AbsFeedItem\$ItemRelation").callMethod("getDissType") ==
                XPrefs<String>("diss_style", "10").toInt()
        }
        probe("comment_filter_mixed_cells") {
            val response = Init.commentResponseClass!!.new()
            val comment = model("com.sup.android.mi.feed.repo.bean.comment.Comment")
                .setObjectField("text", "PPX-TEST-FILTER")
            val cell = model("com.sup.android.mi.feed.repo.bean.comment.CommentFeedCell")
                .setObjectField("comment", comment)
            val blank = model("com.sup.android.mi.feed.repo.bean.comment.ReplyFeedCell")
            val input = arrayListOf(blank, cell)
            response.callMethod("a", input)
            val result = response.callMethod("b") as List<*>
            val removed = FilterPatterns(XPrefs("remove_comments_keywords")).matches("PPX-TEST-FILTER")
            input.size == 2 && result.contains(blank) && result.contains(cell) != removed
        }
        probe("avatar_decoration_mixed_immutable") {
            val user = model("com.sup.android.mi.usercenter.model.UserInfo")
            val decoration = model("com.sup.android.mi.usercenter.model.UserInfo\$Decoration")
            val infoClass = "com.sup.android.mi.usercenter.model.UserInfo\$Decoration\$DecorationInfo".findClass(Init.cl)
            val info = infoClass.declaredConstructors.first().apply { isAccessible = true }.newInstance(decoration)
                .setIntField("decorationType", 2)
            decoration.setObjectField("decorationInfos", listOf(info))
            val empty = model("com.sup.android.mi.usercenter.model.UserInfo\$Decoration")
            user.setObjectField("decorationList", listOf(empty, decoration))
            (user.callMethod("getDecorationList") as List<*>) == listOf(empty)
        }
        probe("send_god_full_local_gate") {
            val comment = model("com.sup.android.mi.feed.repo.bean.comment.Comment")
                .setIntField("sendGodStatus", 3).setBooleanField("hasLiked", true)
            comment.callMethod("getSendGodStatus") == 1
        }
        probe("reply_video_button") {
            val id = "com.sup.android.module.publish.R\$id".findClass(Init.cl)
                .getStaticObjectFieldAs<Int>("iv_comment_video")
            val view = android.widget.ImageView(Init.ctx).apply { this.id = id; visibility = android.view.View.GONE }
            view.visibility == 0
        }
        probe("feed_filter_keyword_and_user") {
            val item = model("com.sup.android.mi.feed.repo.bean.cell.VideoFeedItem")
                .setObjectField("content", "PPX-TEST-FEED")
            val author = model("com.sup.android.mi.usercenter.model.UserInfo").setObjectField("name", "PPX-TEST-USER")
            item.setObjectField("author", author)
            val cell = model("com.sup.android.mi.feed.repo.bean.cell.ItemFeedCell").setObjectField("feedItem", item)
            val keywords = com.akari.ppx.xp.hook.purity.FeedFilter(listOf(true, false, false, false), "[|.*PPX-TEST.*", "")
            val users = com.akari.ppx.xp.hook.purity.FeedFilter(listOf(true, false, false, false), "", "PPX-TEST-USER")
            keywords.matches(cell) && users.matches(cell) && !keywords.matches(null)
        }
        probe("feed_filter_official_and_live") {
            val item = model("com.sup.android.mi.feed.repo.bean.cell.VideoFeedItem")
            val author = model("com.sup.android.mi.usercenter.model.UserInfo")
            val certify = model("com.sup.android.mi.usercenter.model.UserInfo\$CertifyInfo").setObjectField("description", "官方账号")
            author.setObjectField("certifyInfo", certify)
            item.setObjectField("author", author)
            val cell = model("com.sup.android.mi.feed.repo.bean.cell.ItemFeedCell").setObjectField("feedItem", item)
            val filter = com.akari.ppx.xp.hook.purity.FeedFilter(listOf(false, true, true, true), "", "")
            filter.matches(cell) && filter.matches(model("com.sup.android.mi.feed.repo.bean.cell.LiveSaasFeedCell"))
        }
        probe("prevent_mistouch_flag") {
            val controller = uninitialized(Init.vControllerHandlerClass!!)
            controller.callMethod("setGestureEnable", true)
            !controller.getBooleanField("b")
        }
        probe("copy_comment_and_reply") {
            val copy = com.akari.ppx.xp.hook.assist.CopyHook()
            val comment = model("com.sup.android.mi.feed.repo.bean.comment.Comment").setObjectField("text", "测试😊")
            val cell = model("com.sup.android.mi.feed.repo.bean.comment.CommentFeedCell").setObjectField("comment", comment)
            val reply = model("com.sup.android.mi.feed.repo.bean.comment.Reply").setObjectField("text", "回复😊")
            val replyCell = model("com.sup.android.mi.feed.repo.bean.comment.ReplyFeedCell").setObjectField("reply", reply)
            copy.contentText(cell) == "测试😊" && copy.contentText(replyCell) == "回复😊" && copy.contentText(null) == null
        }
        probe("female_label_spannable_and_other_user") {
            checkFemaleColor()
        }
        probe("feed_filter_promotion") {
            val item = model("com.sup.android.mi.feed.repo.bean.cell.VideoFeedItem")
            val field = generateSequence(item.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }.first { it.name == "promotionInfo" }
            field.isAccessible = true
            field.set(item, uninitialized(field.type))
            val cell = model("com.sup.android.mi.feed.repo.bean.cell.ItemFeedCell").setObjectField("feedItem", item)
            com.akari.ppx.xp.hook.purity.FeedFilter(listOf(false, false, true, false), "", "").matches(cell)
        }
        probe("emoji_collection_limits") {
            val clazz = "com.sup.android.emoji.EmojiService".findClass(Init.cl)
            val online = uninitialized(clazz)
            val local = uninitialized(clazz)
            // Null required arguments stop the host before initialization or I/O.
            runCatching { online.callMethod("collectEmoticon", null, 0L, 0L, 0L, null) }
            runCatching { local.callMethod("collectLocalEmoticon", null, 0, 0, false, null) }
            online.getIntField("EMOTICON_MAX_COUNT") == Int.MAX_VALUE &&
                local.getIntField("EMOTICON_MAX_COUNT") == Int.MAX_VALUE &&
                online.callMethod("getEMOTICON_MAX_COUNT") == Int.MAX_VALUE
        }
        probe("ad_getter_and_classification") {
            val ad = model("com.sup.android.mi.feed.repo.bean.ad.AdFeedCell")
            val field = ad.javaClass.getDeclaredField("adInfo").apply { isAccessible = true }
            field.set(ad, uninitialized(field.type))
            val hook = com.akari.ppx.xp.hook.purity.AdHook()
            val match = hook.javaClass.getDeclaredMethod("isCommentAdFeedCell", Any::class.java).apply { isAccessible = true }
            ad.callMethod("getAdInfo") == null && match.invoke(hook, ad) == true &&
                match.invoke(hook, model("com.sup.android.mi.feed.repo.bean.cell.ItemFeedCell")) == false
        }
        probe("splash_ad_gate") {
            val clazz = Init.splashAdClass!!
            val method = clazz.getDeclaredMethod("b").apply { isAccessible = true }
            method.invoke(if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else uninitialized(clazz)) == false
        }
        probe("automatic_update_suppressed") {
            val owner = uninitialized("com.sup.android.m_update.UpdateService".findClass(Init.cl))
            owner.callMethod("checkUpdateByAutomatic", null) == null
        }
        probe("teen_dialog_suppressed") {
            val owner = uninitialized("com.sup.superb.m_teenager.TeenagerService".findClass(Init.cl))
            owner.callMethod("tryShowTeenagerModeDialog", null) == null
        }
        probe("history_post_disabled") {
            val clazz = Init.historyPosterClass!!
            val method = clazz.getDeclaredMethod(Init.historyPoster(), List::class.java).apply { isAccessible = true }
            method.invoke(if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else singleton(clazz), emptyList<Any>()) == true
        }
        probe("location_label_gate") {
            val clazz = Init.locationShowerClass!!
            val method = clazz.declaredMethods.first { it.name == Init.locationShower() && it.parameterCount == 0 }.apply { isAccessible = true }
            method.invoke(if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else uninitialized(clazz)) == true
        }
        probe("footer_layout_reorder") {
            val root = android.widget.LinearLayout(Init.ctx)
            listOf(1, 9, 2, 8, 3, 7, 4).forEach { id -> root.addView(android.view.View(Init.ctx).apply { this.id = id }) }
            val hook = com.akari.ppx.xp.hook.assist.FooterHook()
            hook.reorder(root, listOf(4, 3, 1, 2))
            hook.reorder(root, listOf(4, 3, 1, 2))
            (0 until root.childCount).map { root.getChildAt(it).id } == listOf(4, 9, 3, 8, 1, 7, 2)
        }
        if (XPrefs<Boolean>("modify_message_counts")) probe("message_count_response") {
            val gson = model("com.google.gson.Gson")
            val response = gson.callMethod("fromJson", """{"data":{"count_map":[{"list_type":1,"count":3},{"list_type":5,"count":0}]}}""",
                "com.sup.android.m_message.data.w".findClass(Init.cl))
            val counts = response.getObjectField("data").getObjectFieldAs<List<*>>("count_map")
            counts.size == 2 && counts.all { it.getLongField("count") == 100L }
        }
        if (XPrefs<Boolean>("modify_channels")) probe("channel_stable_id") {
            val category = model("com.sup.superb.feedui.bean.CategoryItem")
                .setIntField("primaryListId", 10).setObjectField("listName", "图文")
            val list = model("com.sup.superb.feedui.bean.CategoryListModel")
                .setObjectField("categoryItems", listOf(category))
            (list.callMethod("getCategoryItems") as List<*>).contains(category)
        }
        XPrefs<String>("enable_double_layout_style").fromJsonList<com.akari.ppx.data.model.CheckBoxItem>().forEach {
            probe("layout_${it.key}") { setting(it.key, !it.checked) == it.checked }
        }
        Log.i("DEVICE_TEST COMPLETE")
    }

    private fun checkFemaleColor(): Boolean {
        val author = model("com.sup.android.mi.usercenter.model.UserInfo")
            .setObjectField("name", "PPX-TEST-FEMALE").setIntField("gender", 2)
        val item = model("com.sup.android.mi.feed.repo.bean.cell.VideoFeedItem").setObjectField("author", author)
        val cell = model("com.sup.android.mi.feed.repo.bean.cell.ItemFeedCell").setObjectField("feedItem", item)
        singleton(Init.feedCellUtilCompanionClass!!).callMethod(Init.getAuthorInfo(), cell)
        val view = android.widget.TextView(Init.ctx).apply {
            id = "com.sup.android.detail.R\$id".findClass(Init.cl).getStaticObjectFieldAs("detail_item_user_name_tv")
            text = android.text.SpannableString("PPX-TEST-FEMALE")
        }
        val expectedColor = FemalePromptColors.argb(XPrefs("female_prompt_color", FemalePromptColors.DEFAULT))
        val highlighted = (view.text as? android.text.Spanned)?.getSpans(0, view.text.length, android.text.style.ForegroundColorSpan::class.java)?.any { it.foregroundColor == expectedColor } == true
        view.text = "另一个用户"
        return highlighted && (view.text !is android.text.Spanned || (view.text as android.text.Spanned).getSpans(0, view.text.length, android.text.style.ForegroundColorSpan::class.java).isEmpty())
    }

    private class SenderFixture {
        @JvmField val V = 0L
        @JvmField val userId = 42L
    }

    internal fun uninitialized(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, clazz)!!
    }

    private fun singleton(clazz: Class<*>): Any = clazz.declaredFields.firstOrNull {
        java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == clazz
    }?.apply { isAccessible = true }?.get(null) ?: clazz.new()

    private fun setting(key: String, default: Any): Any? {
        val clazz = "com.sup.android.social.base.settings.SettingService".findClass(Init.cl)
        return singleton(clazz).callMethod("getValue", key, default, emptyArray<String>())
    }
}
