package com.sup.android.mi.feed.repo.bean.ad

// Host types are unavailable on the JVM. The null getter reproduces the
// remove_ads hook, which must not prevent recognition of an advertisement.
open class AdFeedCell {
    fun getAdInfo(): Any? = null
}

class CommentAdModel

class DerivedAdFeedCell : AdFeedCell()
