package com.diveintocrypto.android.platform

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Contract-bearing wrapper around atomicfu's multiplatform `synchronized`.
 *
 * `kotlin.synchronized` (JVM) declares `callsInPlace(block, EXACTLY_ONCE)`, which
 * lets the compiler prove that a `val` assigned inside the lock body is
 * definitely initialized afterwards. `kotlinx.atomicfu.locks.synchronized` has
 * no such contract, so the same code fails definite-assignment analysis. Adding
 * the contract here restores the original semantics with zero call-site changes.
 */
@OptIn(ExperimentalContracts::class)
internal inline fun <T> synchronized(lock: SynchronizedObject, block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return kotlinx.atomicfu.locks.synchronized(lock, block)
}
