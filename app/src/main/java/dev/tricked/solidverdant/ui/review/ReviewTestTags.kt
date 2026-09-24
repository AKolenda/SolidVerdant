/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.review

/** Stable tags for the Review tab (Inbox, Review day) and the reminder settings. */
object ReviewTestTags {
    const val INBOX_SETTINGS_DONE = "inbox_settings_done"
    const val INBOX_WORK_DAYS = "inbox_settings_work_days"
    const val INBOX_WORK_DAYS_DONE = "inbox_settings_work_days_done"
    const val INBOX_WORK_START = "inbox_settings_work_start"
    const val INBOX_WORK_END = "inbox_settings_work_end"
    const val INBOX_TIME_CONFIRM = "inbox_settings_time_confirm"
    const val INBOX_MIN_GAP = "inbox_settings_min_gap"
    const val INBOX_MAX_DURATION = "inbox_settings_max_duration"
    const val INBOX_STALE_RETRY = "inbox_stale_retry"
    const val INBOX_STALE_DISMISS = "inbox_stale_dismiss"

    const val REVIEW_ACTION_STOP = "review_action_stop"
    const val REVIEW_ACTION_ADJUST_END = "review_action_adjust_end"
    const val REVIEW_ACTION_KEEP_RUNNING = "review_action_keep_running"
    const val REVIEW_ACTION_RETRY = "review_action_retry"
    const val REVIEW_ACTION_ASSIGN = "review_action_assign"
    const val REVIEW_ACTION_SKIP = "review_action_skip"
    const val REVIEW_AGAIN = "review_again"
    const val REVIEW_TIME_CONFIRM = "review_time_picker_confirm"
    const val REVIEW_PROJECT_SEARCH = "review_project_search"
    const val REVIEW_PROJECT_LIST = "review_project_list"

    const val REMINDER_DAILY_SWITCH = "reminder_daily_switch"
    const val REMINDER_EOD_SWITCH = "reminder_eod_switch"
    const val REMINDER_TIME_ROW = "reminder_time_row"
    const val REMINDER_TIME_CONFIRM = "reminder_time_confirm"
    const val REMINDER_ALLOW_NOTIFICATIONS = "reminder_allow_notifications"

    fun segment(segment: ReviewSegment) = "review_segment_${segment.name.lowercase()}"

    fun horizonSegment(option: HorizonOption) = "inbox_settings_horizon_${option.name.lowercase()}"

    fun horizonOption(option: HorizonOption) = "inbox_horizon_option_${option.name.lowercase()}"

    fun workDay(day: java.time.DayOfWeek) = "inbox_settings_work_day_${day.name.lowercase()}"

    fun inboxCheck(name: String) = "inbox_settings_check_${name.lowercase()}"

    fun reviewProject(id: String) = "review_project_$id"
}
