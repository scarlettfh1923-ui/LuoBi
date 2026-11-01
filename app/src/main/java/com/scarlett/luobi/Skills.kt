package com.scarlett.luobi

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 2.4 技能包。一个技能包 = 一份 SKILL.md（YAML frontmatter + 正文）。
 * 内置两个（assets/skills，1.4.0-luobi）；导入的存在 filesDir/skills/<id>/SKILL.md，不进数据库（数据库仍是 v6，无迁移）。
 * id：内置 `b-<name>`，导入 `u-<name 的哈希>`；同名导入覆盖旧版本，且不会覆盖内置。
 */
data class SkillPack(
    val id: String, val name: String, val displayName: String, val version: String, val description: String,
    val builtIn: Boolean, val tagline: String, val kind: String,
) {
    /** 卡片右侧小标签：v1.4.0（去掉 -luobi 之类后缀） */
    val versionChip get() = if (version.isBlank()) "" else "v" + version.substringBefore('-').removePrefix("v")
}

/** 解析 / 导入失败，text 直接给人看 */
class SkillImportError(val text: String) : Exception(text)

object SkillParse {
    const val MAX_CHARS = 200_000
    const val MIN_CHARS = 120

    /** 简易 YAML frontmatter：`key: value`、带引号的值、`>` / `|` 块标量；返回 (字段, 正文) */
    fun frontmatter(textIn: String): Pair<Map<String, String>, String> {
        val text = textIn.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        if (lines.firstOrNull()?.trim() != "---") return emptyMap<String, String>() to text
        var end = -1
        for (i in 1 until lines.size) if (lines[i].trim() == "---") { end = i; break }
        if (end < 0) return emptyMap<String, String>() to text
        val map = LinkedHashMap<String, String>()
        var i = 1
        val key = Regex("^([A-Za-z_][\\w-]*)\\s*:\\s*(.*)$")
        while (i < end) {
            val m = key.find(lines[i]); i++
            if (m == null) continue
            val k = m.groupValues[1].lowercase(); var v = m.groupValues[2].trim()
            if (v == ">" || v == "|" || v == ">-" || v == "|-" || v == ">+" || v == "|+") {
                val parts = ArrayList<String>()
                while (i < end && (lines[i].isBlank() || lines[i].startsWith(" ") || lines[i].startsWith("\t"))) { parts += lines[i].trim(); i++ }
                v = parts.joinToString(if (m.groupValues[2].trim().startsWith("|")) "\n" else " ").trim()
            } else {
                // 续行：缩进的行并入上一条
                while (i < end && lines[i].isNotBlank() && (lines[i].startsWith("  ") || lines[i].startsWith("\t"))) { v += " " + lines[i].trim(); i++ }
                if (v.length >= 2 && ((v.first() == '"' && v.last() == '"') || (v.first() == '\'' && v.last() == '\''))) v = v.substring(1, v.length - 1)
            }
            map[k] = v
        }
        return map to lines.drop(end + 1).joinToString("\n")
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    fun hash(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)

    /** 从 SKILL.md 文本取元数据（不含内置 / 导入判断）。fileStem 是缺 name 时的后备 */
    fun meta(text: String, fileStem: String, builtIn: Boolean, builtInId: String? = null): SkillPack {
        val (fm, body) = frontmatter(text)
        val h1 = body.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim().orEmpty()
        val stem = fileStem.trim().ifBlank { "skill" }
        val name = (fm["name"] ?: "").trim().ifBlank { stem }.take(60)
        val display = (fm["display_name"] ?: fm["title"] ?: "").trim().ifBlank { h1.removeSuffix("Skill").trim() }.ifBlank { name }.take(40)
        val desc = (fm["description"] ?: "").trim()
        val ver = (fm["version"] ?: "").trim().take(24)
        val id = builtInId ?: ("u-" + hash(name))
        val kind = when {
            builtIn && name.startsWith("biographical") -> "bio"
            builtIn && name.startsWith("finance") -> "fin"
            else -> "doc"
        }
        return SkillPack(id, name, display, ver, desc, builtIn, tagline(builtIn, name, desc), kind)
    }

    fun tagline(builtIn: Boolean, name: String, desc: String): String = when {
        builtIn && name.startsWith("biographical") -> "检索史料 · 叙事非虚构 · 文末参考"
        builtIn && name.startsWith("finance") -> "公司兴衰 · 行业周期 · 可溯源长文"
        desc.isBlank() -> "导入的技能包"
        else -> desc.substringBefore('。').substringBefore('：').substringBefore(':').replace(Regex("\\s+"), " ").trim().let { if (it.length > 24) it.take(23) + "…" else it }.ifBlank { "导入的技能包" }
    }

    /** 文件名（含扩展名）→ 去掉扩展名、控制字符；只当文字用，绝不拼进文件路径 */
    fun stem(fileName: String): String = fileName.substringAfterLast('/').substringBeforeLast('.').filter { !it.isISOControl() }.trim().take(40)
}

object Skills {
    private const val ASSET_DIR = "skills"
    /** 内置的顺序 = 创作页卡片顺序（人物传记在前） */
    private val BUILTIN = listOf("biographical-longform-feature", "finance-nonfiction-writer")
    const val MAX_RAW = 8L * 1024 * 1024
    const val MAX_ENTRY = 2L * 1024 * 1024
    private const val MAX_SCAN = 32L * 1024 * 1024

    @Volatile private var builtCache: List<SkillPack>? = null
    private fun dir(ctx: Context) = File(ctx.filesDir, "skills").apply { mkdirs() }

    fun builtIns(ctx: Context): List<SkillPack> = builtCache ?: BUILTIN.mapNotNull { n ->
        try { SkillParse.meta(ctx.assets.open("$ASSET_DIR/$n.md").use { it.readBytes() }.toString(Charsets.UTF_8), n, true, "b-$n") } catch (_: Exception) { null }
    }.also { if (it.size == BUILTIN.size) builtCache = it }

    fun imported(ctx: Context): List<SkillPack> = (dir(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory && it.name.startsWith("u-") && !it.name.endsWith(".tmp") && File(it, "SKILL.md").isFile }.mapNotNull { d ->
        try { SkillParse.meta(File(d, "SKILL.md").readText(), d.name, false).copy(id = d.name) } catch (_: Exception) { null }
    }.sortedBy { it.displayName }

    fun list(ctx: Context): List<SkillPack> = builtIns(ctx) + imported(ctx)
    fun find(ctx: Context, id: String): SkillPack? = list(ctx).firstOrNull { it.id == id }

    /** SKILL.md 全文（用作系统提示词） */
    fun text(ctx: Context, p: SkillPack): String? = try {
        if (p.builtIn) ctx.assets.open("$ASSET_DIR/${p.id.removePrefix("b-")}.md").use { it.readBytes() }.toString(Charsets.UTF_8)
        else File(File(dir(ctx), p.id), "SKILL.md").takeIf { it.isFile }?.readText()
    } catch (_: Exception) { null }

    /** 删除导入的技能包；内置不可删。id 必须是现有导入目录名，不接受路径 */
    fun delete(ctx: Context, id: String): Boolean {
        if (!id.startsWith("u-") || id.contains('/') || id.contains("..")) return false
        val d = File(dir(ctx), id)
        return d.isDirectory && d.deleteRecursively()
    }

    /** 读 uri 里的内容（最多 MAX_RAW）；超过直接拒绝，不全部读进内存 */
    fun readLimited(input: InputStream, max: Long = MAX_RAW): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0L
        while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > max) throw SkillImportError("文件太大（超过 ${max / 1024 / 1024} MB），不像技能包。"); out.write(buf, 0, n) }
        return out.toByteArray()
    }

    data class Imported(val pack: SkillPack, val replaced: Boolean)

    /** SAF 选中的文件 → 导入。耗时的读取 / 解压放在调用方的 IO 线程 */
    fun importUri(ctx: Context, uri: Uri, displayName: String?): Imported {
        val bytes = try { ctx.contentResolver.openInputStream(uri)?.use { readLimited(it) } ?: throw SkillImportError("打不开这个文件。") }
        catch (e: SkillImportError) { throw e } catch (e: SecurityException) { throw SkillImportError("没有权限读取这个文件。") } catch (e: Exception) { throw SkillImportError("读取文件失败：${e.message?.take(60) ?: "未知错误"}") }
        return importBytes(ctx, bytes, displayName ?: uri.lastPathSegment ?: "")
    }

    fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4

    /** zip：不解压到磁盘，只在内存里找 SKILL.md（任意层级，不分大小写；没有就取唯一的 .md）；.md：直接当文本 */
    fun importBytes(ctx: Context, bytes: ByteArray, fileName: String): Imported {
        val (text, stem) = if (isZip(bytes)) extractFromZip(bytes, fileName) else {
            val low = fileName.lowercase()
            if (low.isNotBlank() && !(low.endsWith(".md") || low.endsWith(".markdown") || low.endsWith(".txt"))) throw SkillImportError("只支持 .zip（里面有 SKILL.md）或 .md 文件。")
            decode(bytes) to SkillParse.stem(fileName)
        }
        return install(ctx, text, stem)
    }

    private fun decode(b: ByteArray): String {
        if (b.any { it.toInt() == 0 }) throw SkillImportError("这不是文本文件。")
        return b.toString(Charsets.UTF_8)
    }

    private fun extractFromZip(bytes: ByteArray, fileName: String): Pair<String, String> {
        data class Cand(val path: String, val data: ByteArray)
        val skill = ArrayList<Cand>(); val mds = ArrayList<Cand>()
        var scanned = 0L; var entries = 0
        try {
            ZipInputStream(bytes.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (++entries > 500) throw SkillImportError("压缩包里文件太多，不像技能包。")
                    val path = e.name.replace('\\', '/')
                    if (e.isDirectory || path.startsWith("__MACOSX/") || path.substringAfterLast('/').startsWith("._")) { z.closeEntry(); continue }
                    val base = path.substringAfterLast('/').lowercase()
                    val wanted = base == "skill.md" || base.endsWith(".md")
                    val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var n: Int; var size = 0L
                    while (z.read(buf).also { n = it } >= 0) {
                        size += n; scanned += n
                        if (scanned > MAX_SCAN) throw SkillImportError("压缩包解开后太大，不像技能包。")
                        if (wanted) { if (size > MAX_ENTRY) throw SkillImportError("压缩包里的 ${path.substringAfterLast('/')} 太大（超过 2 MB）。"); out.write(buf, 0, n) }
                    }
                    if (wanted) (if (base == "skill.md") skill else mds) += Cand(path, out.toByteArray())
                    z.closeEntry()
                }
            }
        } catch (e: SkillImportError) { throw e } catch (e: Exception) { throw SkillImportError("这个压缩包打不开，可能已损坏。") }
        val pick = skill.minByOrNull { it.path.count { c -> c == '/' } } ?: mds.singleOrNull()
            ?: throw SkillImportError(if (mds.isEmpty()) "压缩包里没有找到 SKILL.md。" else "压缩包里有多个 .md，却没有叫 SKILL.md 的，不知道选哪个。")
        val folder = pick.path.substringBeforeLast('/', "").substringAfterLast('/')
        val stem = folder.ifBlank { SkillParse.stem(fileName) }.ifBlank { "skill" }
        return decode(pick.data) to stem
    }

    /** 校验 + 写入。同名（frontmatter name）覆盖旧的导入版本 */
    fun install(ctx: Context, text: String, stem: String): Imported {
        if (text.length > SkillParse.MAX_CHARS) throw SkillImportError("内容太长（超过 20 万字），不像技能包。")
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        val (_, body) = SkillParse.frontmatter(clean)
        if (body.trim().length < SkillParse.MIN_CHARS) throw SkillImportError("内容太短，不像一份完整的技能包（SKILL.md 要写清楚角色、流程和交付格式）。")
        val p = SkillParse.meta(clean, stem, false)
        val d = File(dir(ctx), p.id)
        val replaced = d.isDirectory
        val tmp = File(dir(ctx), p.id + ".tmp")
        try {
            tmp.deleteRecursively(); tmp.mkdirs()
            File(tmp, "SKILL.md").writeText(clean)
            if (replaced) d.deleteRecursively()
            if (!tmp.renameTo(d)) throw SkillImportError("保存失败，请检查手机存储空间。")
        } catch (e: SkillImportError) { tmp.deleteRecursively(); throw e } catch (e: Exception) { tmp.deleteRecursively(); throw SkillImportError("保存失败：${e.message?.take(60) ?: "未知错误"}") }
        return Imported(p, replaced)
    }
}
