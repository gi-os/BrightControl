package com.gios.lightcontrol.notify

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.gios.lightcontrol.lock.LightType

/**
 * A score, drawn the way BrightSports draws it.
 *
 * BrightSports posts an ordinary notification -- a title and a text, which is what the shade and
 * every other phone show -- and alongside it five strings that say where the design cuts:
 *
 *     TD   (crest) SEA                      NE 7 · SEA 14      kind / team   ·   value
 *     K. Walker 12 yd run · Myers kick good                    detail
 *     Q2 3:24                                                  foot
 *
 * The lock face and the banner both draw that here, so a score looks the same wherever it lands.
 * A notification without the extras is not a sports card and is drawn exactly as it always was.
 *
 * Reading the `Bundle` stays at the call site, as it does for [NoteText]: a Bundle cannot be built
 * in a unit test, so the keys and the rules live here and the reading lives there.
 */
data class SportsCard(
    /** The left-hand headline: `TD`, `RED ZONE`, `ONE-SCORE GAME`, or the matchup of a live game. */
    val kind: String,
    /** The side it happened to, beside the kind. */
    val team: String? = null,
    /** The right-hand figure: the score, or the team in the red zone. */
    val value: String? = null,
    /** The play, the down and distance, who leads. */
    val detail: String? = null,
    /** The small line at the foot: the period and the clock. */
    val foot: String? = null,
) {

    companion object {
        const val KIND = "com.gios.lightcontrol.extra.SPORT_KIND"
        const val TEAM = "com.gios.lightcontrol.extra.SPORT_TEAM"
        const val VALUE = "com.gios.lightcontrol.extra.SPORT_VALUE"
        const val DETAIL = "com.gios.lightcontrol.extra.SPORT_DETAIL"
        const val FOOT = "com.gios.lightcontrol.extra.SPORT_FOOT"

        /**
         * The card, or null when the notification did not carry one.
         *
         * [kind] is what makes it a card. It is the one line the design cannot do without, and an
         * app that sets the other four without it has not opted in.
         */
        fun of(
            kind: String?,
            team: String? = null,
            value: String? = null,
            detail: String? = null,
            foot: String? = null,
        ): SportsCard? {
            val head = kind?.trim().orEmpty()
            if (head.isEmpty()) return null
            return SportsCard(
                kind = head,
                team = team?.trim()?.takeIf { it.isNotEmpty() },
                value = value?.trim()?.takeIf { it.isNotEmpty() },
                detail = detail?.trim()?.takeIf { it.isNotEmpty() },
                foot = foot?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        /**
         * Which half of `NE 7 · SEA 14` is behind, so the loser can be drawn dim -- the same thing
         * the app's own feed does with a finished game, and the reason a glance at a lock screen
         * answers "who is winning" without reading the numbers.
         *
         * Null when the figure is not two scores: a team abbreviation in the red zone, a margin,
         * a tie.
         */
        fun dimRange(value: String): IntRange? {
            val sep = value.indexOf(" · ")
            if (sep <= 0) return null
            val away = value.substring(0, sep)
            val home = value.substring(sep + 3)
            val a = away.trim().substringAfterLast(' ').toIntOrNull() ?: return null
            val h = home.trim().substringAfterLast(' ').toIntOrNull() ?: return null
            if (a == h) return null
            return if (a < h) 0 until sep else (sep + 3) until value.length
        }
    }
}

/**
 * The card as Views.
 *
 * Built rather than inflated for the same reason the rest of this face is: the window belongs to a
 * service, and a layout file would buy nothing but a trip through the resource system. Every size
 * here is one of [LightType]'s named steps; none of them is a number.
 */
object SportsCardView {

    private val DETAIL = Color.argb(222, 255, 255, 255)
    private val FOOT = Color.argb(140, 255, 255, 255)
    private val DIM = Color.argb(128, 255, 255, 255)
    private val EDGE = Color.argb(90, 255, 255, 255)

    /**
     * @param big the banner's size. The banner is the only thing on the screen and can afford the
     *   subtitle; a lock-face row is one of several and takes the heading.
     * @param bordered draw the hairline box of the design. The banner has its own box already.
     * @param crest the team's crest, when the notification carried one.
     * @param footSuffix appended to the foot line, for the banner's dismissal hint.
     */
    fun build(
        context: Context,
        type: LightType,
        card: SportsCard,
        big: Boolean,
        bordered: Boolean,
        crest: Drawable? = null,
        footSuffix: String? = null,
    ): View {
        val headSize = if (big) type.subtitle else type.heading
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (bordered) {
                background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                    setStroke(hairline(context), EDGE)
                }
                val padH = type.gridPx(0.8f)
                val padV = type.gridPx(0.6f)
                setPadding(padH, padV, padH, padV)
            }
        }

        // The headline: what happened on the left, the figure on the right, one line. The spacer
        // between them is a spacer and not a weight on the kind, because a weighted kind would be
        // measured against the room left after the figure and "ONE-SCORE GAME" would ellipsise on
        // a row that had space for it.
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        head.addView(
            TextView(context).apply {
                typeface = type.condensed ?: Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, headSize)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                text = card.kind.uppercase()
            },
        )
        if (crest != null) {
            head.addView(
                ImageView(context).apply {
                    setImageDrawable(crest)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val side = type.gridPx(if (big) 2.0f else 1.6f)
                    layoutParams = LinearLayout.LayoutParams(side, side).apply {
                        marginStart = type.gridPx(0.4f)
                        marginEnd = type.gridPx(0.35f)
                    }
                },
            )
        }
        if (card.team != null) {
            head.addView(
                TextView(context).apply {
                    typeface = type.condensed ?: Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, headSize)
                    isSingleLine = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { if (crest == null) marginStart = type.gridPx(0.5f) }
                    text = card.team.uppercase()
                },
            )
        }
        head.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        if (card.value != null) {
            head.addView(
                TextView(context).apply {
                    typeface = type.condensedMedium ?: type.condensed ?: Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, headSize)
                    isSingleLine = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = type.gridPx(0.6f) }
                    val figure = card.value.uppercase()
                    text = SportsCard.dimRange(figure)?.let { range ->
                        SpannableString(figure).apply {
                            setSpan(
                                ForegroundColorSpan(DIM),
                                range.first,
                                range.last + 1,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    } ?: figure
                },
            )
        }
        column.addView(head)

        if (card.detail != null) {
            column.addView(
                TextView(context).apply {
                    typeface = type.regular
                    setTextColor(DETAIL)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) type.copy else type.paragraph)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = type.gridPx(0.15f) }
                    text = card.detail
                },
            )
        }
        val foot = listOfNotNull(card.foot, footSuffix).joinToString(" · ").uppercase()
        if (foot.isNotEmpty()) {
            column.addView(
                TextView(context).apply {
                    typeface = type.medium
                    setTextColor(FOOT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, type.superfine)
                    letterSpacing = type.buttonTracking
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = type.gridPx(0.2f) }
                    text = foot
                },
            )
        }
        return column
    }

    /** One physical pixel, the way every other hairline on this face is drawn. */
    private fun hairline(context: Context): Int =
        (context.resources.displayMetrics.density * 0.5f).toInt().coerceAtLeast(1)
}
