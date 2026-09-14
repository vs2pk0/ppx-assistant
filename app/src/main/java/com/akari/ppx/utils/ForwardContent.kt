package com.akari.ppx.utils

/** Resolve the selected reply before its parent; never combine media from different cells. */
internal data class ForwardContent(val title: String, val text: String, val media: List<Media>) {
    data class Media(val urls: List<String>, val video: Boolean, val coverUrls: List<String> = emptyList())

    companion object {
        private fun Any?.read(name: String): Any? = this?.let { value ->
            value.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.invoke(value)
        }

        fun selected(cell: Any?): Any? =
            cell.read("getReply") ?: cell.read("getComment") ?: cell.read("getFeedItem")

        fun text(cell: Any?): String? {
            val item = selected(cell) ?: return null
            return (item.read("getText") ?: item.read("getContent")) as? String
        }

        fun from(cell: Any?): ForwardContent {
            val item = selected(cell) ?: error("没有找到当前内容")
            val title = item.read("getTitle") as? String ?: ""
            val text = (item.read("getText") ?: item.read("getContent")) as? String ?: ""
            val videos = listOf("getOriginDownloadVideoModel", "getVideoDownload", "getVideoDownloadInfo",
                "getVideoHigh", "getVideoInfo", "getVideoMid", "getVideoLow", "getVideoFallback")
                .mapNotNull { item.read(it) }
            val videoUrls = videos.flatMap { urls(it) }.distinct()
            val isVideo = item.javaClass.simpleName.contains("VideoFeedItem") ||
                (item.read("getVideoId") as? String)?.isNotBlank() == true
            kotlin.check(!isVideo || videoUrls.isNotEmpty()) { "当前视频缺少可用地址，请重新加载内容后再转发" }
            kotlin.check(videos.isEmpty() || videoUrls.isNotEmpty()) { "视频缺少下载地址，无法完整转发" }
            val video = videos.firstOrNull()
            val images = (item.read("getImages") ?: item.read("getMultiImage")) as? List<*> ?: emptyList<Any>()
            kotlin.check(video == null || images.isEmpty()) { "当前图文视频混合内容暂不支持转发" }
            val media = if (video != null) listOf(Media(videoUrls, true, urls(item.read("getCoverImage")))) else images.map { image ->
                val display = urls(image)
                val download = urls(image, "getDownloadList")
                val candidates = if (image.read("isGif") == true) {
                    (display + download).filter { it.contains(".gif", true) || it.contains("format=gif", true) } + download + display
                } else display + download
                kotlin.check(candidates.isNotEmpty()) { "有图片缺少下载地址，无法完整转发" }
                Media(candidates.distinct(), false)
            }
            kotlin.check(title.isNotBlank() || text.isNotBlank() || media.isNotEmpty()) { "当前内容为空或暂不支持转发" }
            return ForwardContent(title, text, media)
        }

        private fun urls(model: Any?, method: String = "getUrlList"): List<String> =
            ((model.read(method) as? List<*>) ?: emptyList<Any>()).mapNotNull {
                (it as? String ?: it.read("getUrl") as? String)?.takeIf(String::isNotBlank)
            }
    }
}
