package com.diveintocrypto.android.platform

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Contract-bearing wrapper around atomicfu's multiplatform `synchronized`.
 *
 * `kotlin.synchronized` (JVM) declares `callsInPlace(block, EXACTLY_ONCE)`, which lets the
 * compiler prove that a `val` assigned inside the lock body is definitely initialized afterwards.
 * The atomicfu lock runs the block in place, exactly once, under the lock, and this wrapper keeps
 * that guarantee for call sites. atomicfu's function itself carries no Kotlin contract, so the
 * compiler cannot verify the forwarding call and reports it as a leaked lambda; that is the only
 * reason for the suppression below. Dropping the contract would break definite assignment at the
 * call sites (e.g. PositionsViewModel), so the guarantee is kept and documented here.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
internal inline fun <T> synchronized(lock: SynchronizedObject, block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return kotlinx.atomicfu.locks.synchronized(lock, block)
}
