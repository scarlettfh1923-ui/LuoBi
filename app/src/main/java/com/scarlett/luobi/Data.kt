package com.scarlett.luobi
import kotlinx.coroutines.flow.first

import android.content.Context
import android.content.SharedPreferences
import androidx.room.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一篇文章的一个版本。首次生成 version=1，rootId=自己；每次改写插入新行（version+1，同一个 rootId），可以退回。
 * 保留原始输入、清洗后文字、意图（JSON 对象，键=字段名）、模板、模型和最终提示词。
 */
@Entity(tableName = "notes", indices = [Index("rootId")])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val title: String, val body: String, val tags: String,
    val favorite: Boolean = false,
    val raw: String = "", val cleaned: String = "", val intent: String = "{}",
    val templateId: Long = 0, val templateName: String = "", val model: String = "",
    val finalPrompt: String = "", val rootId: Long = 0, val version: Int = 1, val instruction: String = "",
    @ColumnInfo(defaultValue = "[]") val images: String = "[]",
    /** 1.12：事实校对缓存（JSON：issues/selected/correction/applied…）；空=从未校对 */
    @ColumnInfo(defaultValue = "") val factReportJson: String = "",
    /** 1.15：文章分类（Categories.LIST 之一；空 = 未分类） */
    @ColumnInfo(defaultValue = "") val category: String = "",
    /** 1.15：AI 生成时推荐的分类（选择器里标「AI 推荐」） */
    @ColumnInfo(defaultValue = "") val categoryAi: String = "",
) {
    fun pics() = Pics.decode(images)
    fun tagList() = tags.split(" ", ",", "，").map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }
    fun intentMap(): Map<String, String> = try { val j = JSONObject(intent); j.keys().asSequence().associateWith { j.optString(it) } } catch (_: Exception) { emptyMap() }
    val root get() = if (rootId == 0L) id else rootId
}

/** 写作模板：一段提示词，用 {疑问}{观点}{想写什么}{原文} 引用意图 */
@Entity(tableName = "templates")
data class Template(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val desc: String, val builtIn: Boolean, val prompt: String,
    val defaultModel: String = "", val sort: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

/** 清洗模板（第 ① 步提示词）。fields 用 \n 分隔存储 */
@Entity(tableName = "clean_tpls")
data class CleanTpl(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val prompt: String, val fields: String, val builtIn: Boolean, val active: Boolean,
) { fun fieldList() = fields.split("\n").map { it.trim() }.filter { it.isNotEmpty() } }

/** 自定义发布渠道（密钥另存在加密存储里，键 ch_<id>） */
@Entity(tableName = "channels")
data class Channel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val url: String, val method: String = "POST",
    val authHeader: String = "Authorization", val authPrefix: String = "Bearer ",
    val format: String = "json", val bodyTpl: String = "{\"title\":\"{title}\",\"content\":\"{body}\",\"tags\":\"{tags}\"}",
    val note: String = "",
)

@Dao interface NoteDao {
    /** 每个 root 只取最新版本 */
    @Query("SELECT * FROM notes n WHERE n.version = (SELECT MAX(version) FROM notes m WHERE m.rootId = n.rootId) ORDER BY createdAt DESC")
    fun latest(): Flow<List<Note>>
    @Query("SELECT * FROM notes WHERE id=:id") fun byId(id: Long): Flow<Note?>
    @Query("SELECT * FROM notes WHERE id=:id") suspend fun get(id: Long): Note?
    @Query("SELECT * FROM notes n WHERE n.version = (SELECT MAX(version) FROM notes m WHERE m.rootId = n.rootId) ORDER BY createdAt DESC LIMIT :n") suspend fun recent(n: Int): List<Note>
    @Query("SELECT * FROM notes WHERE rootId=:root ORDER BY version") fun versions(root: Long): Flow<List<Note>>
    @Query("SELECT MAX(version) FROM notes WHERE rootId=:root") suspend fun maxVersion(root: Long): Int?
    /** 1.15：按分类筛选（每个 root 只取最新版），按日期新→旧；给案头日后的分类下拉用 */
    @Query("SELECT * FROM notes n WHERE n.category = :cat AND n.version = (SELECT MAX(version) FROM notes m WHERE m.rootId = n.rootId) ORDER BY createdAt DESC")
    fun byCategory(cat: String): Flow<List<Note>>
    /** 1.15：有文章的分类及篇数 */
    @Query("SELECT category AS name, COUNT(*) AS count FROM notes n WHERE n.category != '' AND n.version = (SELECT MAX(version) FROM notes m WHERE m.rootId = n.rootId) GROUP BY category")
    suspend fun categoryCounts(): List<CategoryCount>
    @Insert suspend fun insert(n: Note): Long
    @Update suspend fun update(n: Note)
    @Query("UPDATE notes SET favorite=:f WHERE rootId=:root OR (rootId = 0 AND id = :root)") suspend fun setFav(root: Long, f: Boolean)
    /** 1.8 修复：rootId=0 的行（root 回落为自己的 id）以前永远匹配不到，删除「成功」但行还在 */
    @Query("DELETE FROM notes WHERE rootId IN (:roots) OR (rootId = 0 AND id IN (:roots))") suspend fun deleteRoots(roots: List<Long>)
    /** 1.8：启动时修复遗留的 rootId=0 行 */
    @Query("UPDATE notes SET rootId = id WHERE rootId = 0") suspend fun repairRoots()
    // 1.15 复查：只改一个字段，避免用旧的 Note 整行覆盖（把刚改正的正文 / 分类写回旧值）
    @Query("UPDATE notes SET factReportJson=:json WHERE id=:id") suspend fun setFactReport(id: Long, json: String)
    @Query("UPDATE notes SET category=:c WHERE rootId=:root OR (rootId = 0 AND id = :root)") suspend fun setCategory(root: Long, c: String)
    @Query("DELETE FROM notes WHERE id=:id") suspend fun deleteId(id: Long)
}
@Dao interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY builtIn DESC, sort, id") fun all(): Flow<List<Template>>
    @Query("SELECT * FROM templates ORDER BY builtIn DESC, sort, id") suspend fun list(): List<Template>
    @Query("SELECT * FROM templates WHERE id=:id") suspend fun get(id: Long): Template?
    @Query("SELECT COUNT(*) FROM templates") suspend fun count(): Int
    @Insert suspend fun insert(t: Template): Long
    @Update suspend fun update(t: Template)
    @Delete suspend fun delete(t: Template)
}
@Dao interface CleanDao {
    @Query("SELECT * FROM clean_tpls ORDER BY builtIn DESC, id") fun all(): Flow<List<CleanTpl>>
    @Query("SELECT * FROM clean_tpls WHERE active=1 LIMIT 1") suspend fun active(): CleanTpl?
    @Query("SELECT * FROM clean_tpls WHERE active=1 LIMIT 1") fun activeFlow(): Flow<CleanTpl?>
    @Query("SELECT COUNT(*) FROM clean_tpls") suspend fun count(): Int
    @Insert suspend fun insert(t: CleanTpl): Long
    @Update suspend fun update(t: CleanTpl)
    @Delete suspend fun delete(t: CleanTpl)
    @Query("UPDATE clean_tpls SET active = (id = :id)") suspend fun activate(id: Long)
}
@Dao interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY id") fun all(): Flow<List<Channel>>
    @Query("SELECT * FROM channels WHERE id=:id") suspend fun get(id: Long): Channel?
    @Insert suspend fun insert(c: Channel): Long
    @Update suspend fun update(c: Channel)
    @Delete suspend fun delete(c: Channel)
}

/** 1.17：后台事实校对任务（每篇一行；中途进度存在 stateJson，进程被杀后接着跑） */
@Entity(tableName = "fact_jobs")
data class FactJob(
    @PrimaryKey val noteId: Long,
    val title: String = "",
    /** running / done / failed */
    val status: String = "running",
    val done: Int = 0,
    val total: Int = 0,
    val stateJson: String = "",
    /** 结果是否看过（没看过 → 红点） */
    val seen: Boolean = true,
    val error: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)
@Dao interface FactJobDao {
    @Query("SELECT * FROM fact_jobs WHERE noteId=:id") suspend fun get(id: Long): FactJob?
    @Query("SELECT * FROM fact_jobs WHERE noteId=:id") fun flow(id: Long): Flow<FactJob?>
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun put(j: FactJob)
    @Query("UPDATE fact_jobs SET done=:done, total=:total, stateJson=:state, updatedAt=:at WHERE noteId=:id") suspend fun progress(id: Long, done: Int, total: Int, state: String, at: Long = System.currentTimeMillis())
    @Query("UPDATE fact_jobs SET seen=1 WHERE noteId=:id") suspend fun markSeen(id: Long)
    /** 1.19：崩溃恢复时把还在「进行中」的校对标为失败，角标不再一直转 */
    @Query("UPDATE fact_jobs SET status='failed', error=:why, updatedAt=:at WHERE status='running'") suspend fun failRunning(why: String, at: Long = System.currentTimeMillis()): Int
}

@Database(entities = [Note::class, Template::class, CleanTpl::class, Channel::class, FactJob::class], version = 6, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun templates(): TemplateDao
    abstract fun cleans(): CleanDao
    abstract fun channels(): ChannelDao
    abstract fun factJobs(): FactJobDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "luobi.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { inst = it }
        }
    }
}

/** 内置模板与清洗模板（全文见 默认写作模板.md / 默认清洗提示词.md） */
object Defaults {
    const val CLEAN_NAME = "标准清洗"
    val CLEAN = """你是一名中文文字编辑。下面是用户的一段口播、语音转写或随手记下的想法，口语化、可能有识别错误。请完成以下三步：

一、清洗文本
1. 删除口头禅和语气词：嗯、呃、啊、就是、然后、那个、对吧、其实吧 等，不影响意思的都删掉。
2. 合并重复：同一句话或同一个意思说了好几遍的，只保留表达最清楚的一次。
3. 纠正语音识别错误：同音字、错别字、断错的句子，按上下文改成正确的词；专有名词、数字、人名、品牌名拿不准时保留原样，不要编造。
4. 理顺语序、补全标点，让句子通顺；但要保留用户自己的说法和语气，不改变原意，不增加原文没有的信息、观点或数据，不扩写。

二、识别意图（最多三项，只输出原文里确实有的，不要硬凑）
- 疑问：用户想弄明白的问题，用一句问句概括。
- 观点：用户明确表达的看法或立场，用一句话概括。
- 想写什么：用户想写成什么样的文章（体裁、风格、篇幅倾向）；原文没提就不输出。

三、推荐模板
从下面的可选模板里选一个最合适的，只填模板名称：
{模板列表}

只输出一个 JSON 对象，不要任何解释，不要 Markdown 代码块。没有识别到的键直接省略，不要输出空字符串或 null：
{"cleaned": "清洗后的完整文字", "疑问": "……", "观点": "……", "想写什么": "……", "recommend": "模板名称"}

用户原文：
{原始文本}"""

    /** 1.9：意图提取提示词（替代清洗：不再输出清洗后文字，口头禅交给生成时处理） */
    const val INTENT_NAME = "意图提取"
    val INTENT = """你是一名中文写作助理。下面是用户的一段口播、语音转写或随手记下的想法，口语化，可能有口头禅和识别错误。请从中提取写作意图，并推荐一个模板。

一、提取意图
- 想写什么：用户想写一篇什么样的文章（主题 + 角度，可带体裁、风格），用一句话概括。这一项必须输出；原文没明说时，根据内容推断最可能想写的文章。
- 观点：用户明确表达的看法或立场，用一句话概括；原文没有就省略这个键。
- 疑问：用户想弄明白、或想替读者回答的问题，用一句问句概括；原文没有就省略这个键。
只写原文里确实有的观点和疑问，不要硬凑，不要添加原文没有的信息。

二、推荐模板
从下面的可选模板里选一个最合适的，只填模板名称：
{模板列表}

只输出一个 JSON 对象，不要任何解释，不要 Markdown 代码块：
{"想写什么": "……", "观点": "……", "疑问": "……", "recommend": "模板名称"}

用户原文：
{原始文本}"""

    /** 1.9 内置默认：头（你是谁 + 用户的意图）+ 写作要求；口播原文由程序附上，不再写 {原文} */
    /** 1.9–1.13 的内置默认（只给 migrateHeaders 识别「没改过的 1.8 默认」用，行为不变） */
    private val templates19 get() = rawTemplates.map { t -> t.copy(prompt = TplHeader.header(t.name) + t.prompt.lines().filter { it.startsWith("写作要求") }.joinToString("\n")) }
    /** 1.14 内置默认：她的 5 个模板 + 标签指令。只用于全新安装和「恢复默认」；已有用户的模板不覆盖 */
    val templates get() = DefaultTemplates114.texts.keys.map { n ->
        Template(name = n, desc = DefaultTemplates114.descs[n].orEmpty(), builtIn = true, sort = DefaultTemplates114.sorts[n] ?: 0, prompt = DefaultTemplates114.prompt(n)!!)
    }
    /** 旧内置名（口播转文章/观点表达/答疑解惑）→ 1.14 新名 */
    fun builtinName(name: String) = DefaultTemplates114.RENAMED[name] ?: name
    private val rawTemplates = listOf(
        Template(name = "财经非虚构", desc = "用真实数据和具体人物讲清一个经济现象", builtIn = true, sort = 0, prompt = """你是一名财经非虚构作者，擅长用真实数据和具体人物讲清一个经济现象。
读者的疑问：{疑问}
我的观点：{观点}
要写成：{想写什么}
写作要求：开头用一个具体场景或人物切入；中间用账本式的数字拆解（收入、成本、利润），讲清楚钱从哪来、流向哪；结尾回到人，给出克制的判断。不编造数据，原文没有的数字用"约""据店主说"等方式说明来源或写成估算。
参考原文：{原文}"""),
        Template(name = "口播转文章", desc = "把口播整理成可以直接发布的文章，保留语气", builtIn = true, sort = 1, prompt = """你是一名编辑，把口播整理成一篇可以直接发布的文章。
我的观点：{观点}
读者的疑问：{疑问}
要写成：{想写什么}
写作要求：保留说话人的语气和用词习惯，按逻辑分段并加 2–4 个小标题；去掉重复，不添加原文没有的观点。
参考原文：{原文}"""),
        Template(name = "观点表达", desc = "亮出观点，给理由和例子，回应反方", builtIn = true, sort = 2, prompt = """你是一名专栏作者。
我的观点：{观点}
它回应的疑问：{疑问}
要写成：{想写什么}
写作要求：第一段就亮出观点；接着给出 2–3 个理由，每个理由配一个具体例子；承认一个反方看法并回应；最后用一句有力的话收尾。
参考原文：{原文}"""),
        Template(name = "答疑解惑", desc = "把一个问题讲明白：是什么、为什么、怎么办", builtIn = true, sort = 3, prompt = """你是一名耐心的讲解者，要把一个问题讲明白。
读者的疑问：{疑问}
我的看法：{观点}
要写成：{想写什么}
写作要求：按"是什么、为什么、怎么办"三段来写；先用一句话给出结论，再展开；术语要用大白话解释；最后给出 3 条可以马上做的建议。
参考原文：{原文}"""),
        Template(name = "小红书风格", desc = "短段落图文笔记，有钩子标题和话题标签", builtIn = true, sort = 4, prompt = """你是一名小红书博主，写一篇图文笔记。
读者的疑问：{疑问}
我的观点：{观点}
要写成：{想写什么}
写作要求：标题不超过 20 字，有钩子；正文用短段落，每段 1–3 句，可以用少量 emoji；分 3–5 个小标题；结尾加互动提问，最后另起一行写 5–8 个话题标签（#开头）。
参考原文：{原文}"""),
    )

    suspend fun ensure(db: AppDb) {
        if (db.templates().count() == 0) templates.forEach { db.templates().insert(it) }
        db.notes().repairRoots()
        migrateHeaders(db)
        if (db.cleans().count() == 0) db.cleans().insert(CleanTpl(name = INTENT_NAME, prompt = INTENT, fields = Vars.DEFAULT_FIELDS.joinToString("\n"), builtIn = true, active = true))
        // 1.9：没改过的内置清洗提示词自动换成意图提取；她改过的原样保留（解析兼容旧格式），页面上提示可恢复默认
        db.cleans().all().first().filter { it.builtIn && it.prompt.trim() == CLEAN.trim() }.forEach {
            db.cleans().update(it.copy(name = INTENT_NAME, prompt = INTENT, fields = Vars.DEFAULT_FIELDS.joinToString("\n")))
        }
        // 1.10：她改过的旧清洗提示词：备份成一条「旧清洗提示词（备份）」，内置这条换成意图提取（不丢她的文字）
        db.cleans().all().first().filter { it.builtIn && isOldClean(it.prompt) }.forEach {
            db.cleans().insert(CleanTpl(name = "旧清洗提示词（备份）", prompt = it.prompt, fields = it.fields, builtIn = false, active = false))
            db.cleans().update(it.copy(name = INTENT_NAME, prompt = INTENT, fields = Vars.DEFAULT_FIELDS.joinToString("\n")))
        }
        if (db.cleans().active() == null) db.cleans().all().first().firstOrNull { it.builtIn }?.let { db.cleans().activate(it.id) }
    }
    /** 旧版清洗提示词：要求输出 cleaned / 清洗后文字 */
    fun isOldClean(p: String) = p.contains("\"cleaned\"") || p.contains("清洗文本") || (p.contains("清洗") && !p.contains("\"想写什么\""))
    fun builtinPrompt(name: String) = DefaultTemplates114.prompt(builtinName(name))
    fun builtinDesc(name: String) = DefaultTemplates114.descs[builtinName(name)]
    /** 1.9 迁移：每个模板开头补【你是谁】【用户的意图】，正文逐字节保留；已有头的不动（幂等） */
    suspend fun migrateHeaders(db: AppDb): Int {
        var n = 0
        db.templates().list().forEach { t ->
            // 从没改过的 1.8 内置默认 → 直接换成 1.9 默认；其余（她自己的正文）只在开头加头
            val old = rawTemplates.firstOrNull { it.name == t.name && it.prompt == t.prompt }
            val m = if (t.builtIn && old != null) templates19.first { it.name == t.name }.prompt else TplHeader.migrate(t.name, t.prompt)
            if (m != t.prompt) { db.templates().update(t.copy(prompt = m)); n++ }
        }
        return n
    }
}

/** 加密存储：所有 Key / Token 只存在本机（EncryptedSharedPreferences，AES256-GCM，主钥匙在 Android Keystore） */
object Secure {
    @Volatile private var p: SharedPreferences? = null
    /** 仅供 JVM 截图测试注入（Robolectric 没有 AndroidKeyStore） */
    fun overrideForTest(sp: SharedPreferences) { p = sp }
    fun prefs(c: Context): SharedPreferences = p ?: synchronized(this) {
        p ?: try {
            val mk = MasterKey.Builder(c.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(c.applicationContext, "secure", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (e: Exception) {
            // 极少数机型 Keystore 损坏：清掉重建（已存的 Key 需要重填）
            c.applicationContext.deleteSharedPreferences("secure")
            val mk = MasterKey.Builder(c.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(c.applicationContext, "secure", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.also { p = it }
    }
    fun get(c: Context, k: String) = prefs(c).getString(k, "") ?: ""
    fun set(c: Context, k: String, v: String) = prefs(c).edit().putString(k, v.trim()).apply()
}

/** 自定义模型（OpenAI 兼容）：名称、Base URL、模型 ID；Key 在 Secure 里（cm_<id>） */
data class CustomModel(val id: String, val name: String, val url: String, val model: String) {
    fun json() = JSONObject().put("id", id).put("name", name).put("url", url).put("model", model)
}

/** 生成时用的一个具体模型 */
data class ModelRef(val ref: String, val label: String, val sub: String) // ref: "p:deepseek" / "c:<id>"

class Settings(val c: Context) {
    private val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun s(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(k: String, v: Any) = p.edit().apply {
        when (v) { is String -> putString(k, v); is Boolean -> putBoolean(k, v); is Int -> putInt(k, v); is Float -> putFloat(k, v) }
    }.apply()
    fun str(k: String, d: String = "") = s(k, d)
    fun set(k: String, v: String) = put(k, v)

    // ----- 模型与密钥 -----
    fun key(pid: String) = Secure.get(c, "key_$pid")
    fun setKey(pid: String, v: String) = Secure.set(c, "key_$pid", v)
    fun model(pid: String) = s("model_$pid", Providers.text(pid).models.first().first)
    fun setModel(pid: String, v: String) = put("model_$pid", v)
    fun url(pid: String) = s("url_$pid", Providers.text(pid).url)
    fun setUrl(pid: String, v: String) = put("url_$pid", v)

    var customs: List<CustomModel>
        get() = try { val a = JSONArray(s("customs", "[]")); (0 until a.length()).map { a.getJSONObject(it).let { o -> CustomModel(o.getString("id"), o.optString("name"), o.optString("url"), o.optString("model")) } } } catch (_: Exception) { emptyList() }
        set(v) = put("customs", JSONArray().apply { v.forEach { put(it.json()) } }.toString())
    fun customKey(id: String) = Secure.get(c, "cm_$id")
    fun setCustomKey(id: String, v: String) = Secure.set(c, "cm_$id", v)

    /** 已配置 Key 的模型（第 ③ 步只列这些） */
    fun configured(): List<ModelRef> =
        Providers.text.filter { key(it.id).isNotBlank() }.map { pr -> ModelRef("p:${pr.id}", pr.name, model(pr.id)) } +
            customs.filter { customKey(it.id).isNotBlank() && it.url.isNotBlank() && it.model.isNotBlank() }.map { ModelRef("c:${it.id}", it.name.ifBlank { "自定义" }, it.model) }

    /** 1.10 灵感：每日选题提示词（空 = 默认）与启用的热榜来源 */
    var inspirePrompt: String get() = s("inspirePrompt", "").ifBlank { Inspire.DEFAULT_PROMPT }; set(v) = put("inspirePrompt", if (v.trim() == Inspire.DEFAULT_PROMPT.trim()) "" else v)
    var inspireSources: Set<String> get() = s("inspireSrc", HotSources.all.joinToString(",") { it.id }).split(",").filter { it.isNotBlank() }.toSet(); set(v) = put("inspireSrc", v.joinToString(","))
    /** 1.11 事实校对：提示词 + 检索源（deepseek / wiki / bocha）+ 博查 Key */
    var factPrompt: String get() = s("factPrompt", "").ifBlank { FactCheck.DEFAULT_PROMPT }; set(v) = put("factPrompt", if (v.trim() == FactCheck.DEFAULT_PROMPT.trim()) "" else v)
    var factSearch: String get() = s("factSearch", "deepseek"); set(v) = put("factSearch", v)
    /** 校对用的聊天模型 ref；空 = 与写作相同（默认）。与「事实检索」独立。 */
    var factModel: String get() = s("factModel", ""); set(v) = put("factModel", v)
    var bochaKey: String get() = Secure.get(c, "bocha_key"); set(v) = Secure.set(c, "bocha_key", v)
    /** 1.16 元宝搜索（WSA）API Key，加密存本机 */
    var wsaKey: String get() = Secure.get(c, "wsa_key"); set(v) = Secure.set(c, "wsa_key", v)
    var defaultModel get() = s("defaultModel", ""); set(v) = put("defaultModel", v)
    /** 0 短 · 1 中 · 2 长 */
    var length get() = p.getInt("length", 1); set(v) = put("length", v)
    var lengths get() = Lengths.decode(s("lengths", Lengths.encode(Lengths.DEFAULT))); set(v) = put("lengths", Lengths.encode(v))
    fun lengthRange() = lengths[length.coerceIn(0, 2)]
    var imgSources: List<ImgSource>
        get() = try { val a = JSONArray(s("imgSources", "")); (0 until a.length()).map { ImgSource.from(a.getJSONObject(it)) }.ifEmpty { ImgSource.DEFAULT } } catch (_: Exception) { ImgSource.DEFAULT }
        set(v) = put("imgSources", JSONArray().apply { v.forEach { put(it.json()) } }.toString())
    /** 图片下方「图片来自 Pexafy」小字；关掉后阅读页和所有导出都不显示 */
    var showCredit get() = p.getBoolean("showCredit", true); set(v) = put("showCredit", v)
    var shareBar: List<String>
        get() = ShareBar.decode(if (p.contains("shareBar")) s("shareBar", "") else null, ChannelIds.allIds(c))
        set(v) = put("shareBar", ShareBar.encode(v))
    var picMin get() = p.getInt("picMin", 2); set(v) = put("picMin", v)
    var picMax get() = p.getInt("picMax", 3); set(v) = put("picMax", v)
    /** any / landscape / portrait */
    var picOrient get() = s("picOrient", "any"); set(v) = put("picOrient", v)
    /** 1.16.2 DeepSeek 文案生成：深度思考（默认关）+ 温度（默认 0.7，范围 0–2，新键，老用户自然落到 0.7） */
    var dsThink get() = p.getBoolean("dsThink", false); set(v) = put("dsThink", v)
    var dsTemp: Float get() = p.getFloat("dsTemp", 0.7f).coerceIn(0f, 2f); set(v) = put("dsTemp", (Math.round(v.coerceIn(0f, 2f) * 10) / 10f))
    var attachRaw get() = p.getBoolean("attachRaw", true); set(v) = put("attachRaw", v)

    // ----- 阅读 / 出图 -----
    var fontSize get() = p.getFloat("fontSize", 17f); set(v) = put("fontSize", v)
    var readMode: Int
        get() {
            if (!p.getBoolean("readModeV2", false)) { val nv = migrateReadMode(p.getInt("readMode", 0)); p.edit().putInt("readMode", nv).putBoolean("readModeV2", true).apply() }
            return p.getInt("readMode", 0).coerceIn(0, readPalettes.lastIndex)
        }
        set(v) { p.edit().putInt("readMode", v).putBoolean("readModeV2", true).apply() }
    var readFont get() = s("readFont", "sans"); set(v) = put("readFont", v)
    /** 1.16.1：配图行距改为真实倍数 1.4–2.0（默认 1.7）；旧值（1.0–1.3）迁移一次 */
    var cardSpacing: Float
        get() {
            if (!p.getBoolean("cardLineMigrated", false)) {
                val nv = if (p.contains("cardSpacing")) Render.migrateSpacing(p.getFloat("cardSpacing", 1.0f)) else Render.LINE_DEFAULT
                p.edit().putFloat("cardLine", nv).putBoolean("cardLineMigrated", true).apply()
            }
            return p.getFloat("cardLine", Render.LINE_DEFAULT).coerceIn(Render.LINE_MIN, Render.LINE_MAX)
        }
        set(v) { p.edit().putFloat("cardLine", v.coerceIn(Render.LINE_MIN, Render.LINE_MAX)).putBoolean("cardLineMigrated", true).apply() }
    var style get() = p.getInt("style", 0); set(v) = put("style", v)
    var cardRatio get() = p.getInt("cardRatio", 0); set(v) = put("cardRatio", v)
    /** 1.19 小红书：封面风格 / 正文版式 */
    var coverKind get() = p.getInt("coverKind", 0); set(v) = put("coverKind", v)
    var bodyKind get() = p.getInt("bodyKind", 0); set(v) = put("bodyKind", v)
    var libSort get() = p.getInt("libSort", 0); set(v) = put("libSort", v)
    /** 配图模型（可选） */
    var imgOn get() = p.getBoolean("imgOn", false); set(v) = put("imgOn", v)
    var imgProv get() = s("imgProv", "silicon"); set(v) = put("imgProv", v)
    fun imgKey(id: String) = Secure.get(c, "ikey_$id")
    fun setImgKey(id: String, v: String) = Secure.set(c, "ikey_$id", v)
    fun imgModel(id: String) = s("imodel_$id", Providers.image(id).models.first().first)
    fun setImgModel(id: String, v: String) = put("imodel_$id", v)

    // ----- 发布渠道 -----
    var notionTarget get() = s("notionTarget", ""); set(v) = put("notionTarget", v)
    /** page / database */
    var notionType get() = s("notionType", "page"); set(v) = put("notionType", v)
    var notionTitleProp get() = s("notionTitleProp", ""); set(v) = put("notionTitleProp", v)
    var notionTagsProp get() = s("notionTagsProp", ""); set(v) = put("notionTagsProp", v)
    var notionLabel get() = s("notionLabel", ""); set(v) = put("notionLabel", v)
    var yuqueNs get() = s("yuqueNs", ""); set(v) = put("yuqueNs", v)
    var yuquePublic get() = p.getBoolean("yuquePublic", false); set(v) = put("yuquePublic", v)
    var telegraphName get() = s("telegraphName", "落笔"); set(v) = put("telegraphName", v)

    /** 常用渠道（最多 4 个）：渠道 id 列表 */
    var pinned: List<String>
        get() = s("pinned", "long,html,notion").split(",").filter { it.isNotBlank() }.take(4)
        set(v) = put("pinned", v.take(4).joinToString(","))
}

/** 1.0 → 1.2：notes 增加 images 列（插图 JSON），旧数据保留 */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE notes ADD COLUMN images TEXT NOT NULL DEFAULT '[]'") }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE templates ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE notes ADD COLUMN factReportJson TEXT NOT NULL DEFAULT ''") }
}

/** 1.15：notes 增加 category 列（非破坏，旧文章为空） */
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE notes ADD COLUMN category TEXT NOT NULL DEFAULT ''"); db.execSQL("ALTER TABLE notes ADD COLUMN categoryAi TEXT NOT NULL DEFAULT ''") }
}

/** 1.17：后台事实校对任务表 */
val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `fact_jobs` (`noteId` INTEGER NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, `done` INTEGER NOT NULL, `total` INTEGER NOT NULL, `stateJson` TEXT NOT NULL, `seen` INTEGER NOT NULL, `error` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`noteId`))")
    }
}

data class CategoryCount(val name: String, val count: Int)
