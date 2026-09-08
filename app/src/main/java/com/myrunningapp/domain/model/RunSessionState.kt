package com.myrunningapp.domain.model

/**
 * The lifecycle of an in-progress run, owned by the tracking service
 * (wired up in milestone 2).
 *
 * ```
 * IDLE ──start──▶ COUNTDOWN ──▶ TRACKING ⇄ PAUSED ──finish──▶ FINISHED
 *                    │
 *                    └──cancel──▶ IDLE
 * ```
 */
enum class RunSessionState {
    IDLE,
    COUNTDOWN,
    TRACKING,
    PAUSED,
    FINISHED,
}
