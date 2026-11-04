/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Grant flags for the document contracts below.
 *
 * The read permission the picker hands back is, without the persistable bit, good only until
 * the Activity that received the result is gone. An outbox that holds a picked file across
 * pages - and across minutes while it waits for a device to show up - outlives exactly that,
 * and the later read then fails with a `Permission Denial` from the provider. Asking for the
 * persistable grant is what lets the app keep the file readable; taking it is the caller's
 * job (see [LsManager.addStaged]), because only a documents-tree uri can hold one and the
 * call throws for the ones that cannot.
 *
 * A plain `val` rather than a `const`: kapt's stub generation trips over an operator call
 * inside a constant field's initializer and dies with an internal compiler error.
 */
private val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

/**
 * Contract for openable documents.
 * This is needed because OpenDocument doesn't specify [Intent.CATEGORY_OPENABLE]
 */
class OpenableDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return super.createIntent(context, input)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(GRANT_FLAGS)
    }
}

/**
 * Multi-select sibling of [OpenableDocument]: lets LocalSend pick several files at once.
 * Same OPENABLE-category fix - the base contract omits it, so non-openable providers
 * would otherwise list entries we cannot actually read back.
 */
class OpenableMultipleDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return super.createIntent(context, input)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(GRANT_FLAGS)
    }
}