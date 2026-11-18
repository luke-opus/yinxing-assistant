package li.gkd.app.data

import li.gkd.app.util.format
import li.gkd.app.util.getShowActivityId
import li.gkd.db.ActionLog

val ActionLog.showActivityId: String?
    get() = getShowActivityId(appId, activityId)

val ActionLog.date: String
    get() = ctime.format("yyyy-MM-dd HH:mm:ss SSS")

val ActionLog.actionDescription: String
    get() {
        val label = when (action) {
            "click", "clickNode" -> "点击控件"
            "clickCenter" -> "点击坐标"
            "longClick", "longClickNode" -> "长按控件"
            "longClickCenter" -> "长按坐标"
            "back" -> "返回上一页"
            "swipe" -> "滑动"
            "none" -> "仅匹配（未点击）"
            null -> "旧版记录未保存行为"
            else -> action.orEmpty()
        }
        val x = positionX
        val y = positionY
        return if (x != null && y != null) {
            "$label（${x.toInt()}, ${y.toInt()}）"
        } else {
            label
        }
    }
