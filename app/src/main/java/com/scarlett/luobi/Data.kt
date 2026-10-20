package com.scarlett.luobi

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
) {
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
    @Query("SELECT * FROM notes WHERE rootId=:root ORDER BY version") fun versions(root: Long): Flow<List<Note>>
    @Query("SELECT MAX(version) FROM notes WHERE rootId=:root") suspend fun maxVersion(root: Long): Int?
    @Insert suspend fun insert(n: Note): Long
    @Update suspend fun update(n: Note)
    @Query("UPDATE notes SET favorite=:f WHERE rootId=:root") suspend fun setFav(root: Long, f: Boolean)
    @Query("DELETE FROM notes WHERE rootId IN (:roots)") suspend fun deleteRoots(roots: List<Long>)
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

@Database(entities = [Note::class, Template::class, CleanTpl::class, Channel::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun templates(): TemplateDao
    abstract fun cleans(): CleanDao
    abstract fun channels(): ChannelDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "luobi.db").build().also { inst = it }
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

    val templates = listOf(
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
        if (db.cleans().count() == 0) db.cleans().insert(CleanTpl(name = CLEAN_NAME, prompt = CLEAN, fields = Vars.DEFAULT_FIELDS.joinToString("\n"), builtIn = true, active = true))
    }
    fun builtinPrompt(name: String) = templates.firstOrNull { it.name == name }?.prompt
}

/** 加密存储：所有 Key / Token 只存在本机（EncryptedSharedPreferences，AES256-GCM，主钥匙在 Android Keystore） */
object Secure {
    @Volatile private var p: SharedPreferences? = null
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

    var defaultModel get() = s("defaultModel", ""); set(v) = put("defaultModel", v)
    /** 0 短 · 1 中 · 2 长 */
    var length get() = p.getInt("length", 1); set(v) = put("length", v)
    var lengths get() = Lengths.decode(s("lengths", Lengths.encode(Lengths.DEFAULT))); set(v) = put("lengths", Lengths.encode(v))
    fun lengthRange() = lengths[length.coerceIn(0, 2)]
    var attachRaw get() = p.getBoolean("attachRaw", true); set(v) = put("attachRaw", v)

    // ----- 阅读 / 出图 -----
    var fontSize get() = p.getFloat("fontSize", 17f); set(v) = put("fontSize", v)
    var readMode get() = p.getInt("readMode", 0); set(v) = put("readMode", v)
    var readFont get() = s("readFont", "sans"); set(v) = put("readFont", v)
    var cardSpacing get() = p.getFloat("cardSpacing", 1.1f).coerceIn(1.0f, 1.3f); set(v) = put("cardSpacing", v.coerceIn(1.0f, 1.3f))
    var style get() = p.getInt("style", 0); set(v) = put("style", v)
    var cardRatio get() = p.getInt("cardRatio", 0); set(v) = put("cardRatio", v)
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
