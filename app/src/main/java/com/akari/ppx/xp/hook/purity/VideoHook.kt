@file:Suppress("unused")

package com.akari.ppx.xp.hook.purity

import android.content.Context
import android.util.Base64
import com.google.gson.JsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.downloadGodVideoGate
import com.akari.ppx.xp.Init.feedCellUtilCompanionClass
import com.akari.ppx.xp.Init.getVideoDownload
import com.akari.ppx.xp.Init.videoDownloadConfigClass
import com.akari.ppx.xp.Init.videoDownloadHelperClass
import com.akari.ppx.xp.hook.SwitchHook
import java.util.Collections
import java.util.WeakHashMap

class VideoHook : SwitchHook("save_video") {
    private val originVideoIdCache = ConcurrentHashMap<Long, String>()
    private val videoHints = Collections.synchronizedMap(WeakHashMap<Any, VideoHint>())
    private val configHints = Collections.synchronizedMap(WeakHashMap<Any, VideoHint>())

    private data class VideoHint(
        val preferredUri: String?,
        val itemId: Long,
        val source: String
    )

    private data class VideoRuntime(
        val helperClass: Class<*>,
        val configClass: Class<*>,
        val entryMethod: Method,
        val finalMethod: Method,
        val forbiddenMethod: Method,
        val forbiddenCallbackClass: Class<*>,
        val gateMethod: Method
    )

    override fun onHook() {
        val videoModelClass = requireClass(
            "com.sup.android.base.model.VideoModel",
            "video model"
        )
        val runtime = resolveRuntime(videoModelClass)
        hookFeedDownloadHints()
        hookGodVideoSource()

        Log.i(
            "VideoHook install helper=${runtime.helperClass.name} config=${runtime.configClass.name} " +
                    "entry=${runtime.entryMethod.name} final=${runtime.finalMethod.name} " +
                    "forbidden=${runtime.forbiddenMethod.name} gate=${runtime.gateMethod.name}"
        )
        hookEntry(runtime.entryMethod, "entry")
        hookDownload(runtime.finalMethod, runtime.configClass, "finalDownload")
        hookForbidden(runtime.forbiddenMethod, runtime.forbiddenCallbackClass, "forbidden")
        runtime.gateMethod.replaceMethod { false }
    }

    private fun resolveRuntime(videoModelClass: Class<*>): VideoRuntime {
        val listenerClass = requireClass(
            "com.ss.android.socialbase.downloader.depend.IDownloadListener",
            "download listener"
        )
        val forbiddenCallbackClass =
            requireClass(
                "com.ss.android.socialbase.downloader.depend.IDownloadForbiddenCallback",
                "forbidden callback"
            )
        val function1Class = requireClass(
            "kotlin.jvm.functions.Function1",
            "function1"
        )

        val cachedHelperClass = runCatching { videoDownloadHelperClass }.onFailure {
            Log.w("VideoHook resolve cached helper invalid, fallback to reflection")
            Log.w(it)
        }.getOrNull()
        val cachedConfigClass = runCatching { videoDownloadConfigClass }.onFailure {
            Log.w("VideoHook resolve cached config invalid, fallback to reflection")
            Log.w(it)
        }.getOrNull()
        val cachedGate = runCatching { downloadGodVideoGate() }.onFailure {
            Log.w("VideoHook resolve cached gate invalid, fallback to reflection")
            Log.w(it)
        }.getOrNull()
        Log.i(
            "VideoHook resolve cache helper=${cachedHelperClass?.name} config=${cachedConfigClass?.name} " +
                    "gate=$cachedGate"
        )

        if (
            cachedHelperClass != null &&
            cachedConfigClass != null &&
            !cachedGate.isNullOrBlank()
        ) {
            val methods = cachedHelperClass.declaredMethods
            val gateMethod = methods.firstOrNull { it.name == cachedGate }
            val entryMethod = findVoidMethod(
                methods,
                Context::class.java,
                videoModelClass,
                cachedConfigClass,
                listenerClass,
                videoModelClass,
                Boolean::class.java,
                function1Class
            )
            val finalMethod = findVoidMethod(
                methods,
                Context::class.java,
                videoModelClass,
                cachedConfigClass,
                listenerClass,
                Boolean::class.java,
                function1Class
            )
            val forbiddenMethod = findBooleanMethod(
                methods,
                String::class.java,
                cachedConfigClass,
                forbiddenCallbackClass
            )
            if (entryMethod != null && finalMethod != null && gateMethod != null) {
                Log.i("VideoHook resolve use cached helper/config")
                return VideoRuntime(
                    cachedHelperClass,
                    cachedConfigClass,
                    entryMethod,
                    finalMethod,
                    forbiddenMethod
                        ?: throw IllegalArgumentException("VideoHook forbidden method missing"),
                    forbiddenCallbackClass,
                    gateMethod
                )
            }
            Log.w("VideoHook resolve cached helper/config incomplete, fallback to reflection")
        } else {
            Log.i("VideoHook resolve fallback to reflection")
        }

        val helperClass = findClassCompat(
            "com.sup.android.video.g",
            "com.sup.android.video.VideoDownloadHelper"
        ) ?: throw IllegalArgumentException("VideoHook helper class missing")
        val configClass = findClassCompat(
            "com.sup.android.video.f",
            "com.sup.android.video.VideoDownLoadConfig"
        ) ?: throw IllegalArgumentException("VideoHook config class missing")
        val methods = helperClass.declaredMethods
        val entryMethod = findVoidMethod(
            methods,
            Context::class.java,
            videoModelClass,
            configClass,
            listenerClass,
            videoModelClass,
            Boolean::class.java,
            function1Class
        ) ?: throw IllegalArgumentException("VideoHook entry method missing")
        val finalMethod = findVoidMethod(
            methods,
            Context::class.java,
            videoModelClass,
            configClass,
            listenerClass,
            Boolean::class.java,
            function1Class
        ) ?: throw IllegalArgumentException("VideoHook final method missing")
        val forbiddenMethod = findBooleanMethod(
            methods,
            String::class.java,
            configClass,
            forbiddenCallbackClass
        ) ?: throw IllegalArgumentException("VideoHook forbidden method missing")
        val gateMethod = methods.firstOrNull { it.parameterTypes.isEmpty() && isBooleanType(it.returnType) }
            ?: throw IllegalArgumentException("VideoHook gate method missing")
        Log.i(
            "VideoHook resolve reflection helper=${helperClass.name} config=${configClass.name} " +
                    "final=${finalMethod.name} forbidden=${forbiddenMethod.name} gate=${gateMethod.name}"
        )
        return VideoRuntime(
            helperClass,
            configClass,
            entryMethod,
            finalMethod,
            forbiddenMethod,
            forbiddenCallbackClass,
            gateMethod
        )
    }

    private fun requireClass(name: String, label: String): Class<*> {
        return runCatching { name.findClass(cl) }.getOrElse {
            throw IllegalArgumentException("VideoHook $label class missing: $name", it)
        }
    }

    private fun findClassCompat(vararg names: String): Class<*>? {
        for (name in names) {
            val clazz = runCatching { name.findClass(cl) }.getOrNull()
            if (clazz != null) {
                return clazz
            }
        }
        return null
    }

    private fun findVoidMethod(methods: Array<Method>, vararg parameterTypes: Class<*>): Method? {
        return methods.firstOrNull { method ->
            method.returnType == Void.TYPE && method.parameterTypes.contentEquals(parameterTypes)
        }
    }

    private fun findBooleanMethod(methods: Array<Method>, vararg parameterTypes: Class<*>): Method? {
        return methods.firstOrNull { method ->
            isBooleanType(method.returnType) && method.parameterTypes.contentEquals(parameterTypes)
        }
    }

    private fun isBooleanType(clazz: Class<*>): Boolean {
        return clazz == java.lang.Boolean.TYPE || clazz == java.lang.Boolean::class.javaObjectType
    }

    private fun safeCallString(target: Any?, methodName: String): String? {
        return runCatching { target?.callMethodOrNullAs<String>(methodName) }.getOrNull()
    }

    private fun safeCallLong(target: Any?, methodName: String): Long? {
        return runCatching { target?.callMethodOrNullAs<Long>(methodName) }.getOrNull()
    }

    private fun safeCallBoolean(target: Any?, methodName: String): Boolean? {
        return runCatching { target?.callMethodAs<Boolean>(methodName) }.getOrNull()
    }

    private fun configItemId(config: Any?): Long? {
        return safeCallLong(config, "getA") ?: safeCallLong(config, "getItemId")
    }

    private fun configRetryUseGodVideoModel(config: Any?): Boolean? {
        return safeCallBoolean(config, "getJ") ?: safeCallBoolean(config, "getRetryUseGodVideoModel")
    }

    private fun shortValue(value: String?, maxLength: Int = 96): String {
        if (value.isNullOrEmpty()) {
            return "<empty>"
        }
        return if (value.length <= maxLength) value else value.take(maxLength) + "...(" + value.length + ")"
    }

    private fun firstUrl(videoModel: Any?): String? {
        val urlList = runCatching { videoModel?.callMethodAs<List<*>>("getUrlList") }.getOrNull() ?: return null
        return urlList.firstOrNull()?.callMethodOrNullAs("getUrl")
    }

    private fun hintSummary(hint: VideoHint?): String {
        if (hint == null) {
            return "hint=<null>"
        }
        return "hint{itemId=${hint.itemId},source=${hint.source},preferred=${shortValue(hint.preferredUri)}}"
    }

    private fun modelSummary(videoModel: Any?): String {
        if (videoModel == null) {
            return "model=<null>"
        }
        return buildString {
            append("model{class=").append(videoModel.javaClass.name)
            append(",uri=").append(shortValue(safeCallString(videoModel, "getUri")))
            append(",firstUrl=").append(shortValue(firstUrl(videoModel)))
            append(",").append(hintSummary(findHint(videoModel)))
            append("}")
        }
    }

    private fun configSummary(config: Any?): String {
        if (config == null) {
            return "config=<null>"
        }
        return buildString {
            append("config{class=").append(config.javaClass.name)
            append(",A=").append(configItemId(config))
            append(",J=").append(configRetryUseGodVideoModel(config))
            append(",").append(hintSummary(configHints[config]))
            append("}")
        }
    }

    private fun cellSummary(absFeedCell: Any?): String {
        if (absFeedCell == null) {
            return "cell=<null>"
        }
        val feedItem = absFeedCell.callMethodOrNull("getFeedItem")
        val comment = absFeedCell.callMethodOrNull("getComment")
        val reply = absFeedCell.callMethodOrNull("getReply")
        return buildString {
            append("cell{class=").append(absFeedCell.javaClass.name)
            append(",feedItem=").append(feedItem?.javaClass?.name)
            append(",feedItemId=").append(safeCallLong(feedItem, "getItemId"))
            append(",comment=").append(comment?.javaClass?.name)
            append(",commentItemId=").append(safeCallLong(comment, "getItemId"))
            append(",commentAliasItemId=").append(safeCallLong(comment, "getAliasItemId"))
            append(",reply=").append(reply?.javaClass?.name)
            append(",replyItemId=").append(safeCallLong(reply, "getItemId"))
            append(",replyAliasItemId=").append(safeCallLong(reply, "getAliasItemId"))
            append("}")
        }
    }

    private fun hookFeedDownloadHints() {
        val dockerContextClass = findClassCompat("com.sup.superb.dockerbase.misc.DockerContext") ?: return
        val absFeedCell = findClassCompat("com.sup.android.mi.feed.repo.bean.cell.AbsFeedCell") ?: return
        listOf(
            listOf("com.sup.superb.feedui.util.ai", "com.sup.superb.feedui.util.ShareHelper"),
            listOf("com.sup.superb.feedui.util.m", "com.sup.superb.feedui.util.FeedVideoDownloadHelper"),
            listOf("com.sup.android.detail.util.j", "com.sup.android.detail.util.DetailVideoDownloadHelper"),
            listOf("com.sup.android.utils.br", "com.sup.android.utils.ShareVideoDownloadHelper")
        ).forEach { classNames ->
            runCatching {
                val helperClass = findClassCompat(*classNames.toTypedArray())
                    ?: throw IllegalArgumentException("VideoHook hint helper missing: ${classNames.first()}")
                val helperLabel = helperClass.name
                val methods = helperClass.declaredMethods
                    .filter { method ->
                        method.returnType == Void.TYPE &&
                                method.parameterTypes.size >= 2 &&
                                method.parameterTypes[0] == dockerContextClass &&
                                method.parameterTypes[1] == absFeedCell
                    }
                Log.i("VideoHook hint hook register class=$helperLabel count=${methods.size}")
                methods
                    .forEach { method ->
                        method.hookBeforeMethod { param ->
                            val cell = param.args.getOrNull(1)
                            if (cell != null) {
                                val hookTag = "${helperLabel.substringAfterLast('.')}.${method.name}/${method.parameterTypes.size}"
                                Log.d("VideoHook[$hookTag] helper hit ${cellSummary(cell)}")
                                cacheHintsFromCell(
                                    cell,
                                    hookTag
                                )
                            }
                        }
                    }
            }.onFailure(Log::e)
        }
    }

    private fun hookGodVideoSource() {
        val companionClass = feedCellUtilCompanionClass ?: return
        runCatching {
            companionClass.declaredMethods.firstOrNull { method ->
                method.name == "Q" &&
                        method.parameterTypes.size == 1 &&
                        method.returnType.name == "com.sup.android.base.model.VideoModel"
            }?.replaceMethod {
                Log.i("VideoHook ignore AbsFeedCellUtil.Q god video model")
                null
            }
        }.onFailure(Log::e)
    }

    private fun hookEntry(method: Method, tag: String) {
        method.hookBeforeMethod { param ->
            val mainModel = param.args.getOrNull(1)
            val config = param.args.getOrNull(2)
            val godModel = param.args.getOrNull(4)
            Log.i(
                "VideoHook[$tag] enter ${modelSummary(mainModel)} ${modelSummary(godModel)} ${configSummary(config)}"
            )
            if (config != null) {
                val hint = findHint(mainModel) ?: findHint(godModel)
                if (hint != null) {
                    configHints[config] = hint
                    Log.d("VideoHook[$tag] bind config ${hintSummary(hint)} ${configSummary(config)}")
                }
                if (godModel != null) {
                    runCatching { config.callMethod("b", false) }
                        .recoverCatching { config.callMethod("setRetryUseGodVideoModel", false) }
                        .onFailure(Log::e)
                    param.args[4] = null
                    Log.i("VideoHook[$tag] ignore god video branch for save_video ${configSummary(config)}")
                }
            }
        }
    }

    private fun hookDownload(
        method: Method,
        configClass: Class<*>,
        tag: String
    ) {
        method.replaceMethod { param ->
            CoroutineScope(Dispatchers.Main).launch {
                val videoModel = param.args.getOrNull(1)
                if (videoModel == null) {
                    Log.w("VideoHook[$tag] skip: no video model")
                    invokeOriginal(param)
                    return@launch
                }
                val config = param.args.getOrNull(2)
                if (config == null || !configClass.isInstance(config)) {
                    Log.w("VideoHook[$tag] skip: invalid config=${config?.javaClass?.name}")
                    invokeOriginal(param)
                    return@launch
                }
                Log.i("VideoHook[$tag] start ${modelSummary(videoModel)} ${configSummary(config)}")

                val result = runCatching {
                    resolveDownloadUrl(videoModel, config, tag)
                }

                result.onSuccess { url ->
                    Log.i("VideoHook[$tag] resolved url=${shortValue(url, 180)}")
                    rewriteVideoModel(videoModel, url, tag)
                }.onFailure {
                    Log.e("VideoHook[$tag] resolve failed")
                    Log.e(it)
                }

                invokeOriginal(param)
            }
        }
    }

    private fun hookForbidden(
        method: Method,
        forbiddenCallbackClass: Class<*>,
        tag: String
    ) {
        method.replaceMethod { param ->
            val uri = param.args.getOrNull(0) as? String
            val config = param.args.getOrNull(1)
            val callback = param.args.getOrNull(2)
            if (uri.isNullOrEmpty()) {
                Log.w("VideoHook[$tag] skip: empty uri")
                return@replaceMethod false
            }
            if (callback == null || !forbiddenCallbackClass.isInstance(callback)) {
                Log.w("VideoHook[$tag] skip: invalid callback=${callback?.javaClass?.name}")
                return@replaceMethod false
            }
            Log.i(
                "VideoHook[$tag] start uri=${shortValue(uri)} ${configSummary(config)} callback=${callback.javaClass.name}"
            )
            return@replaceMethod runCatching {
                val resolvedUri = resolvePreferredUri(null, config, uri, tag)
                val cleanUrl = resolveDownloadUrlByUri(resolvedUri)
                Log.i(
                    "VideoHook[$tag] replace forbidden url success resolvedUri=${shortValue(resolvedUri)} " +
                            "cleanUrl=${shortValue(cleanUrl, 180)}"
                )
                callback.callMethod("onCallback", arrayListOf(cleanUrl))
                true
            }.getOrElse {
                Log.e("VideoHook[$tag] replace forbidden url failed")
                Log.e(it)
                false
            }
        }
    }

    private suspend fun invokeOriginal(param: HookParam) = withContext(Dispatchers.Main) {
        param.invokeOriginalMethod()
    }

    private suspend fun resolveDownloadUrl(videoModel: Any, config: Any, tag: String): String {
        val fallbackUri = videoModel.callMethodAs<String>("getUri")
        val uri = withContext(Dispatchers.IO) {
            resolvePreferredUri(videoModel, config, fallbackUri, tag)
        }
        Log.d(
            "VideoHook[$tag] resolveDownloadUrl selectedUri=${shortValue(uri)} fallback=${shortValue(fallbackUri)} " +
                    "${configSummary(config)}"
        )
        return withContext(Dispatchers.IO) {
            resolveDownloadUrlByUri(uri)
        }
    }

    private fun resolvePreferredUri(videoModel: Any?, config: Any?, fallbackUri: String, tag: String): String {
        Log.d(
            "VideoHook[$tag] resolvePreferredUri start ${modelSummary(videoModel)} ${configSummary(config)} " +
                    "fallback=${shortValue(fallbackUri)}"
        )
        findHint(videoModel)?.preferredUri?.takeIf { it.isNotEmpty() }?.let { hintedUri ->
            Log.d("VideoHook[$tag] branch=modelHint uri=${shortValue(hintedUri)} fallback=${shortValue(fallbackUri)}")
            return hintedUri
        }
        val configHint = configHints[config]
        configHint?.preferredUri?.takeIf { it.isNotEmpty() }?.let { hintedUri ->
            Log.d(
                "VideoHook[$tag] branch=configHint uri=${shortValue(hintedUri)} " +
                        "fallback=${shortValue(fallbackUri)} ${hintSummary(configHint)}"
            )
            return hintedUri
        }
        if (configHint != null && configHint.itemId > 0L && config != null) {
            val originUri = runCatching { resolveOriginVideoUri(configHint, config, tag) }.getOrElse {
                Log.e("VideoHook[$tag] resolve config hinted item failed")
                Log.e(it)
                ""
            }
            if (originUri.isNotEmpty()) {
                Log.d(
                    "VideoHook[$tag] branch=configHintItem uri=${shortValue(originUri)} " +
                            "fallback=${shortValue(fallbackUri)} ${hintSummary(configHint)}"
                )
                return originUri
            }
        }
        val useOrigin = config?.let(::shouldUseOriginVideoId) ?: false
        Log.d("VideoHook[$tag] branch=originFlag useOrigin=$useOrigin ${configSummary(config)}")
        if (useOrigin) {
            val originUri = runCatching { resolveOriginVideoUri(configHints[config], config!!, tag) }.getOrElse {
                Log.e("VideoHook[$tag] resolve origin uri failed")
                Log.e(it)
                ""
            }
            if (originUri.isNotEmpty()) {
                Log.d("VideoHook[$tag] branch=origin uri=${shortValue(originUri)} fallback=${shortValue(fallbackUri)}")
                return originUri
            }
        }
        if (fallbackUri.isNotEmpty()) {
            Log.d("VideoHook[$tag] branch=fallbackModel uri=${shortValue(fallbackUri)}")
            return fallbackUri
        }
        if (config != null) {
            val originUri = resolveOriginVideoUri(configHints[config], config, tag)
            Log.d("VideoHook[$tag] branch=emptyModelOrigin uri=${shortValue(originUri)}")
            return originUri
        }
        throw IllegalArgumentException("VideoHook uri missing")
    }

    private fun shouldUseOriginVideoId(config: Any): Boolean {
        return configRetryUseGodVideoModel(config) ?: false
    }

    private fun resolveOriginVideoUri(hint: VideoHint?, config: Any, tag: String): String {
        Log.d("VideoHook[$tag] resolveOriginVideoUri start ${hintSummary(hint)} ${configSummary(config)}")
        hint?.preferredUri?.takeIf { it.isNotEmpty() }?.let {
            Log.d("VideoHook[$tag] resolveOriginVideoUri use hint preferred=${shortValue(it)}")
            return it
        }
        val itemId = hint?.itemId?.takeIf { it > 0L } ?: (configItemId(config) ?: 0L)
        if (itemId <= 0L) {
            throw IllegalArgumentException("VideoHook[$tag] invalid itemId=$itemId")
        }
        originVideoIdCache[itemId]?.let { cached ->
            if (cached.isNotEmpty()) {
                Log.d("VideoHook[$tag] hit origin cache item_id=$itemId uri=${shortValue(cached)}")
                return cached
            }
        }
        val detailUrl = "https://h5.pipix.com/bds/webapi/item/detail/?item_id=$itemId"
        Log.d("VideoHook[$tag] request origin item detail item_id=$itemId url=$detailUrl")
        val originUri = httpGet(detailUrl)
            .fromJsonElement()["data"]["item"]["origin_video_id"].asString
        if (originUri.isNotEmpty()) {
            originVideoIdCache[itemId] = originUri
        }
        Log.d("VideoHook[$tag] resolveOriginVideoUri result item_id=$itemId uri=${shortValue(originUri)}")
        return originUri
    }

    private fun cacheHintsFromCell(absFeedCell: Any, tag: String) {
        val primaryModel = resolvePrimaryVideoModel(absFeedCell)
        val godModel = resolveGodVideoModel(absFeedCell)
        val hint = buildHintFromCell(absFeedCell)
        Log.d(
            "VideoHook[$tag] cacheHintsFromCell ${cellSummary(absFeedCell)} " +
                    "${modelSummary(primaryModel)} ${modelSummary(godModel)} ${hintSummary(hint)}"
        )
        if (hint == null) {
            Log.w("VideoHook[$tag] cacheHintsFromCell skip: no hint from cell")
            return
        }
        cacheHint(primaryModel, hint, tag)
        cacheHint(godModel, hint, tag)
    }

    private fun cacheHint(videoModel: Any?, hint: VideoHint, tag: String) {
        if (videoModel == null) {
            Log.d("VideoHook[$tag] cacheHint skip model=null ${hintSummary(hint)}")
            return
        }
        videoHints[videoModel] = hint
        Log.d("VideoHook[$tag] cache hint ${modelSummary(videoModel)} ${hintSummary(hint)}")
    }

    private fun findHint(videoModel: Any?): VideoHint? {
        if (videoModel == null) {
            return null
        }
        return videoHints[videoModel]
    }

    private fun resolvePrimaryVideoModel(absFeedCell: Any): Any? {
        val companion = resolveFeedCellUtilCompanionInstance() ?: return null
        return companion.callMethodOrNull(getVideoDownload(), absFeedCell)
    }

    private fun resolveGodVideoModel(absFeedCell: Any): Any? {
        val companionClass = feedCellUtilCompanionClass ?: return null
        val companion = resolveFeedCellUtilCompanionInstance() ?: return null
        val method = companionClass.declaredMethods.firstOrNull { method ->
            method.name == "Q" &&
                    method.parameterTypes.size == 1 &&
                    method.returnType.name == "com.sup.android.base.model.VideoModel"
        } ?: return null
        return runCatching {
            method.isAccessible = true
            method.invoke(companion, absFeedCell)
        }.getOrNull()
    }

    private fun resolveFeedCellUtilCompanionInstance(): Any? {
        val companionClass = feedCellUtilCompanionClass ?: return null
        val singletonField = companionClass.declaredFields.firstOrNull { it.type == companionClass } ?: return null
        return companionClass.getStaticObjectField(singletonField.name)
    }

    private fun buildHintFromCell(absFeedCell: Any): VideoHint? {
        val originItem = resolveOriginItem(absFeedCell)
        val originSource = resolveOriginUriSource(originItem)
        val preferredUri = originSource.first
        val itemId = resolveItemId(originItem, absFeedCell)
        if (preferredUri.isNullOrEmpty() && itemId <= 0L) {
            Log.w(
                "VideoHook buildHintFromCell empty originItem=${originItem?.javaClass?.name} ${cellSummary(absFeedCell)}"
            )
            return null
        }
        val hint = VideoHint(preferredUri, itemId, originSource.second)
        Log.d(
            "VideoHook buildHintFromCell originItem=${originItem?.javaClass?.name} ${hintSummary(hint)} " +
                    "${cellSummary(absFeedCell)}"
        )
        return hint
    }

    private fun resolveOriginItem(absFeedCell: Any): Any? {
        absFeedCell.callMethodOrNull("getFeedItem")?.let { return it }
        absFeedCell.callMethodOrNull("getComment")?.let { comment ->
            comment.callMethodOrNull("getOriginItem")?.let { return it }
        }
        absFeedCell.callMethodOrNull("getReply")?.let { reply ->
            reply.callMethodOrNull("getOriginItem")?.let { return it }
            reply.callMethodOrNull("getComment")?.callMethodOrNull("getOriginItem")?.let { return it }
        }
        return null
    }

    private fun resolveOriginUriSource(originItem: Any?): Pair<String?, String> {
        if (originItem == null) {
            return null to "originItem=null"
        }
        originItem.callMethodOrNullAs<String>("getOriginVideoId")?.takeIf { it.isNotEmpty() }?.let {
            return it to "originItem.getOriginVideoId"
        }
        originItem.callMethodOrNull("getOriginDownloadVideoModel")
            ?.callMethodOrNullAs<String>("getUri")
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it to "originItem.getOriginDownloadVideoModel.getUri" }
        originItem.callMethodOrNull("getVideoDownload")
            ?.callMethodOrNullAs<String>("getUri")
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it to "originItem.getVideoDownload.getUri" }
        return null to "originItem.noUri"
    }

    private fun resolveItemId(originItem: Any?, absFeedCell: Any): Long {
        originItem?.callMethodOrNullAs<Long>("getItemId")?.takeIf { it > 0L }?.let { return it }
        absFeedCell.callMethodOrNull("getComment")?.let { comment ->
            comment.callMethodOrNullAs<Long>("getItemId")?.takeIf { it > 0L }?.let { return it }
            comment.callMethodOrNullAs<Long>("getAliasItemId")?.takeIf { it > 0L }?.let { return it }
        }
        absFeedCell.callMethodOrNull("getReply")?.let { reply ->
            reply.callMethodOrNullAs<Long>("getItemId")?.takeIf { it > 0L }?.let { return it }
            reply.callMethodOrNullAs<Long>("getAliasItemId")?.takeIf { it > 0L }?.let { return it }
            reply.callMethodOrNull("getComment")?.callMethodOrNullAs<Long>("getItemId")?.takeIf { it > 0L }?.let { return it }
        }
        return 0L
    }

    private fun resolveDownloadUrlByUri(uri: String): String {
        val ts = System.currentTimeMillis()
        val signature = "com.bytedance.common.utility.DigestUtils".findClass(cl).callStaticMethod(
            "md5Hex",
            "ts${ts}userbdsversion1video${uri}vtypemp4f425df23905d4ee38685e276072faa0c"
        ) as String
        val requestUrl = "https://i.snssdk.com/video/play/1/bds/$ts/$signature/mp4/$uri"
        Log.d(
            "VideoHook resolveDownloadUrlByUri request uri=${shortValue(uri)} ts=$ts sign=${signature.take(12)} " +
                    "url=${shortValue(requestUrl, 180)}"
        )
        val response = httpGet(requestUrl)
        val decodedUrl = decodeMaxQualityUrl(response)
        Log.d(
            "VideoHook resolveDownloadUrlByUri decoded uri=${shortValue(uri)} url=${shortValue(decodedUrl, 180)}"
        )
        return decodedUrl
    }

    private fun rewriteVideoModel(videoModel: Any, url: String, tag: String) {
        val urlList = videoModel.callMethodAs<List<*>>("getUrlList")
        val oldUri = safeCallString(videoModel, "getUri")
        val oldFirstUrl = firstUrl(videoModel)
        Log.i(
            "VideoHook[$tag] rewrite start count=${urlList.size} oldUri=${shortValue(oldUri)} " +
                    "oldFirstUrl=${shortValue(oldFirstUrl, 180)} newUrl=${shortValue(url, 180)}"
        )
        urlList.forEach { entry ->
            entry?.callMethod("setUrl", url)
        }
        runCatching {
            // CDN playback URLs use /video/tos/... and contain no video ID.
            // Keep the model's original ID instead of treating a valid URL as an error.
            val uri = extractVideoUri(url) ?: oldUri
            if (!uri.isNullOrBlank()) videoModel.callMethod("setUri", uri)
            Log.d(
                "VideoHook[$tag] rewrite done newUri=${shortValue(uri)} " +
                        "newFirstUrl=${shortValue(firstUrl(videoModel), 180)}"
            )
        }.onFailure(Log::e)
    }

    private fun extractVideoUri(url: String): String? {
        val marker = "/mp4/"
        val index = url.lastIndexOf(marker)
        if (index < 0) return null
        return url.substring(index + marker.length).substringBefore('?').substringBefore('#')
            .takeIf { it.isNotBlank() && '/' !in it }
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/123.0 Mobile Safari/537.36"
            )
        }
        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299 || body.isEmpty()) {
                throw IllegalStateException(
                    "VideoHook httpGet failed code=$responseCode url=${shortValue(url, 180)} body=${shortValue(body, 180)}"
                )
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeMaxQualityUrl(response: String): String {
        val videoList = response.fromJsonElement()["video_info"]["data"]["video_list"]
        for (index in 5 downTo 0) {
            val encoded = runCatching { videoList["video_$index"]["main_url"].asString }.getOrNull()
            if (!encoded.isNullOrEmpty()) {
                return String(Base64.decode(encoded, 0))
            }
        }
        throw IllegalArgumentException("VideoHook main_url missing")
    }
}
