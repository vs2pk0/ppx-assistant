package com.akari.ppx.utils

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fixture(): BackupArchive.Data {
        val image = temp.newFile("image-${System.nanoTime()}.gif").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val video = temp.newFile("video-${System.nanoTime()}.mp4").apply { writeBytes(ByteArray(130_000) { (it % 251).toByte() }) }
        val cover = temp.newFile("cover-${System.nanoTime()}.jpg").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        return BackupArchive.Data(linkedMapOf("flag" to true, "literal" to "false", "integer" to 7,
            "long" to 1234567890123L, "float" to 1.25f, "set" to setOf("甲", "乙"), "storage_dir" to "/old/path"),
            listOf(EmoticonGroup("表情", mutableListOf(CustomEmoticon("tos/example", 12, 34, true, image.absolutePath))), EmoticonGroup("空表情")),
            listOf(VideoGroup("视频", mutableListOf(CustomVideo("v1", "测试视频", video.absolutePath, cover.absolutePath, 12345, 720, 1280))), VideoGroup("空视频")))
    }

    private fun archive(data: BackupArchive.Data = fixture()): File {
        val target = File(temp.root, "backup-${System.nanoTime()}.zip")
        BackupArchive.write(target, data, { File(it.local!!) })
        return target
    }

    private fun prepare(file: File): BackupArchive.Prepared = file.inputStream().use {
        BackupArchive.read(it, File(temp.root, "stage-${System.nanoTime()}"))
    }

    private fun entries(file: File): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) { val e = zip.nextEntry ?: break; entries[e.name] = zip.readBytes(); zip.closeEntry() }
        }
        return entries
    }

    private fun mutated(file: File, change: (LinkedHashMap<String, ByteArray>) -> Unit): File {
        val entries = entries(file); change(entries)
        return File(temp.root, "mutated-${System.nanoTime()}.zip").also { output ->
            ZipOutputStream(output.outputStream()).use { zip -> entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            } }
        }
    }

    private fun manifest(entries: MutableMap<String, ByteArray>, change: (JsonObject) -> Unit) {
        val root = JsonParser.parseString(String(entries.getValue("manifest.json"))).asJsonObject
        change(root); entries["manifest.json"] = root.toString().toByteArray()
    }

    private fun fails(block: () -> Unit) {
        try { block(); fail("Expected validation to fail") } catch (e: Exception) { assertTrue(e.message != null) }
    }

    @Test fun fullArchiveRoundTripPreservesBytesMetadataAndTypesAcrossDirectories() {
        val original = fixture(); val file = archive(original)
        val destination = temp.newFolder("new-device")
        var settings: Map<String, Any>? = null
        prepare(file).use { prepared ->
            val restored = BackupArchive.restore(prepared, destination) { settings = it }
            assertEquals(BackupArchive.settings(original.settings), settings)
            val e = restored.emoticons[0].emojis.single()
            assertEquals(original.emoticons[0].emojis[0].copy(local = e.local), e)
            assertArrayEquals(File(original.emoticons[0].emojis[0].local!!).readBytes(), File(e.local!!).readBytes())
            val v = restored.videos[0].videos.single()
            assertTrue(v.path.startsWith(destination.absolutePath)); assertTrue(v.cover.startsWith(destination.absolutePath))
            assertEquals(original.videos[0].videos[0].copy(path = v.path, cover = v.cover), v)
            assertArrayEquals(File(original.videos[0].videos[0].path).readBytes(), File(v.path).readBytes())
            assertArrayEquals(File(original.videos[0].videos[0].cover).readBytes(), File(v.cover).readBytes())
            assertEquals("空视频", restored.videos[1].name); assertEquals("空表情", restored.emoticons[1].name)
        }
        assertEquals(1.25f, settings!!["float"]); assertEquals("false", settings!!["literal"])
        assertEquals(7, settings!!["integer"]); assertEquals(1234567890123L, settings!!["long"])
    }

    @Test fun zipContainsActualFilesAndNoOriginalDevicePaths() {
        val file = archive(); val entries = entries(file)
        assertEquals(4, entries.size)
        val json = String(entries.getValue("manifest.json"))
        assertFalse(json.contains(temp.root.absolutePath)); assertFalse(json.contains("/old/path"))
        assertTrue(json.contains("\"assets\""))
    }

    @Test fun oldJsonPreservesBooleanStringAndDoesNotRestoreOldStoragePathOrRevision() {
        val raw = """{"schema":1,"app":"皮友助手","settings":{"a":true,"b":"false","storage_dir":"/old","settings_revision":"old"},"emoticons":[{"name":"旧分组","emojis":[{"uri":"tos/id","local":"/does-not-exist"}]}]}"""
        BackupArchive.legacy(raw).use { p ->
            val restored = BackupArchive.restore(p, temp.newFolder("legacy")) {}
            assertEquals(mapOf("a" to true, "b" to "false"), restored.settings)
            assertNull(restored.emoticons.single().emojis.single().local)
            assertFalse(p.portable)
        }
    }

    @Test fun repeatedImportMergesWithoutDuplicateEntriesAndKeepsUnrelatedGroups() {
        val data = fixture()
        val current = data.copy(emoticons = data.emoticons + EmoticonGroup("保留"), videos = data.videos + VideoGroup("保留视频"))
        val merged = BackupArchive.merge(BackupArchive.merge(current, data), data)
        assertEquals(3, merged.emoticons.size); assertEquals(1, merged.emoticons.sumOf { it.emojis.size })
        assertEquals(3, merged.videos.size); assertEquals(1, merged.videos.sumOf { it.videos.size })
        assertEquals(current.emoticons, merged.emoticons)
    }

    @Test fun restoringSameArchiveReusesMediaAndRepairsACorruptedCopy() {
        val file = archive()
        val root = temp.newFolder("repeat-restore")
        prepare(file).use { prepared ->
            val first = BackupArchive.restore(prepared, root) {}
            val media = File(root, "restored-media")
            val originalFiles = media.listFiles()!!.map { it.name }.toSet()
            assertEquals(3, originalFiles.size)

            val cover = File(first.videos.first().videos.single().cover)
            val expected = cover.readBytes()
            cover.writeBytes(ByteArray(expected.size))

            assertEquals(first, BackupArchive.restore(prepared, root) {})
            assertArrayEquals(expected, cover.readBytes())
            assertEquals(originalFiles, media.listFiles()!!.map { it.name }.toSet())
        }
    }

    @Test fun legacyRestoreKeepsOnlyMediaFromTheConfiguredDirectory() {
        val root = temp.newFolder("legacy-local")
        val local = File(root, "image.gif").apply { writeText("image") }
        val outside = temp.newFile("unrelated.txt").apply { writeText("private") }
        val raw = """{"schema":1,"app":"皮友助手","settings":{},"emoticons":[{"name":"旧分组","emojis":[{"uri":"tos/local","local":"${local.path}"},{"uri":"tos/other","local":"${outside.path}"}]}]}"""
        BackupArchive.legacy(raw).use { prepared ->
            val restored = BackupArchive.restore(prepared, root) {}
            assertEquals(listOf(local.canonicalPath, null), restored.emoticons.single().emojis.map { it.local })
        }
    }

    @Test fun failedSettingsTransactionRollsBackBothManifestsAndMedia() {
        val root = temp.newFolder("rollback")
        val emoji = File(root, "custom_emoticons.json").apply { writeText("[]") }
        val video = File(root, "custom_videos.json").apply { writeText("[]") }
        prepare(archive()).use { p -> fails { BackupArchive.restore(p, root) { error("test settings failure") } } }
        assertEquals("[]", emoji.readText()); assertEquals("[]", video.readText())
        assertEquals(setOf(emoji.name, video.name), root.list()!!.toSet())
    }

    @Test fun rollbackOnFreshInstallDoesNotLeaveManifests() {
        val root = temp.newFolder("rollback-empty")
        prepare(archive()).use { p -> fails { BackupArchive.restore(p, root) { error("failure") } } }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun corruptedAssetRejectedBeforeRestore() {
        val damaged = mutated(archive()) { e -> val key = e.keys.first { it.startsWith("media/") }; e[key] = byteArrayOf(0, 0) }
        fails { prepare(damaged) }
    }

    @Test fun missingAssetRejectedBeforeRestore() {
        fails { prepare(mutated(archive()) { e -> e.remove(e.keys.first { it.startsWith("media/") }) }) }
    }

    @Test fun pathTraversalRejectedAndStagingRemoved() {
        val damaged = mutated(archive()) { it["../escape.mp4"] = byteArrayOf(1) }
        val stage = File(temp.root, "bad-stage")
        fails { damaged.inputStream().use { BackupArchive.read(it, stage) } }
        assertFalse(stage.exists()); assertFalse(File(temp.root, "escape.mp4").exists())
    }

    @Test fun assetReferenceNotInManifestRejected() {
        val damaged = mutated(archive()) { e -> manifest(e) { root ->
            root["videos"].asJsonArray[0].asJsonObject["videos"].asJsonArray[0].asJsonObject.addProperty("path", "media/missing.mp4")
        } }
        fails { prepare(damaged) }
    }

    @Test fun duplicateAssetManifestRejected() {
        val damaged = mutated(archive()) { e -> manifest(e) { root -> root["assets"].asJsonArray.add(root["assets"].asJsonArray[0].deepCopy()) } }
        fails { prepare(damaged) }
    }

    @Test fun unsupportedVersionAndForeignBackupRejected() {
        fails { prepare(mutated(archive()) { e -> manifest(e) { it.addProperty("schema", 999) } }) }
        fails { BackupArchive.legacy("""{"schema":1,"app":"other","settings":{}}""") }
        fails { BackupArchive.legacy("{}") }
    }

    @Test fun invalidSettingsOrPartialEmojiManifestDoesNotSilentlySucceed() {
        fails { prepare(mutated(archive()) { e -> manifest(e) { it["settings"].asJsonObject.add("broken", JsonObject()) } }) }
        fails { prepare(mutated(archive()) { e -> manifest(e) { root ->
            root["emoticons"].asJsonArray[0].asJsonObject["emojis"].asJsonArray[0].asJsonObject.remove("uri")
        } }) }
    }

    @Test fun missingMediaDoesNotLeaveAValidLookingExport() {
        val data = fixture().let { d -> d.copy(videos = d.videos.map { g -> g.copy(videos = g.videos.map { it.copy(path = "/missing.mp4") }.toMutableList()) }) }
        val target = File(temp.root, "incomplete.zip")
        fails { BackupArchive.write(target, data, { File(it.local!!) }) }
        assertFalse(target.exists()); assertFalse(File(temp.root, "incomplete.zip.tmp").exists())
    }

    @Test fun oneSourceFileUsedByMultipleGroupsStoredOnce() {
        val data = fixture(); val v = data.videos[0].videos[0]
        val file = archive(data.copy(videos = data.videos + VideoGroup("另一组", mutableListOf(v))))
        assertEquals(4, entries(file).size)
        prepare(file).use { p -> assertEquals(2, p.data.videos.sumOf { it.videos.size }) }
    }
}
