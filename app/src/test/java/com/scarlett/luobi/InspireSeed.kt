package com.scarlett.luobi
import android.content.Context
import java.text.SimpleDateFormat
import java.util.*
/** 截图测试用：写入当天的灵感缓存，避免测试时联网 */
object InspireSeed {
    val topics = listOf(
        Topic("宝马员工争着离职，补偿成了“理财产品”", "微博", "大厂离职潮背后的补偿账本", "裁员补偿正在变成员工的另一种收益"),
        Topic("越粗糙的短剧，为什么越停不下来", "知乎", "粗糙剧情如何精准击中爽点", "短剧卖的不是故事，是情绪的即时兑现"),
        Topic("热梗会不会伤害中文表达", "B站", "从“绝绝子”看网络语言的生命周期", "热梗是方言，不是病毒"),
        Topic("NBA 揭幕战背后的转播生意", "抖音", "一场比赛能卖出多少钱", "体育正在变成流量的附属品"),
        Topic("DeepSeek 单卡每小时收入 38 美金意味着什么", "知乎", "算一笔大模型的生意账", "AI 的价格战才刚刚开始"))
    fun seed(c: Context) { c.getSharedPreferences("inspire", Context.MODE_PRIVATE).edit()
        .putString("date", SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date()))
        .putString("data", Inspire.encode(Inspire.Result(topics, 1792544400000L, listOf("抖音热榜")))).apply() }
}
