package li.gkd.app.data

import java.net.URLEncoder

// XAdSkip additions, 2026-09-13. SPDX-License-Identifier: GPL-3.0-only
object XAdSkipConfig {
    const val updateManifestUrl = "https://xadskip.com/update.json"
    const val draftSubscriptionId = 2026091301L
    const val homePageUrl = "https://xadskip.com/"
    const val useNotesUrl = "https://xadskip.com/#use-notes"
    const val websitePrivacyUrl = "https://xadskip.com/#privacy"
    const val licensesUrl = "https://xadskip.com/#licenses"
    const val feedbackUrl = "https://feedback.xadskip.com/"
    const val feedbackEmailUrl = "mailto:1345134186@qq.com"
    const val upstreamRepositoryUrl = "https://github.com/gkd-kit/gkd"

    fun feedbackUrlForVersion(versionName: String, versionCode: Int): String =
        feedbackUrl + "?xadskipVersion=" + URLEncoder.encode(versionName, "UTF-8") +
            "&xadskipVersionCode=" + versionCode
}
