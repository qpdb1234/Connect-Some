/*
 * Copyright (c) 2022  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

#ifndef AVNC_CLIENTEX_H
#define AVNC_CLIENTEX_H

#include <jni.h>
#include "Cursor.h"

/**
 * We attach some additional data to every rfbClient.
 * ClientEx is used as wrapper for this data.
 */
struct ClientEx {
    // Reference to managed `RfbClientNative`
    jobject managedClient;

    // Although frame width & height are maintained in rfbClient, those values
    // are modified before our MallocFrameBuffer callback is triggered, and
    // we cannot protect them with a mutex. So we maintain the framebuffer
    // size here, protected with fbMutex.
    int fbRealWidth;
    int fbRealHeight;

    // Cursor data used for client-side cursor rendering
    Cursor *cursor;

    // Size of the framebuffer the OpenGL texture was last allocated for, or 0 when the
    // texture holds no storage yet (first frame, a server resize, or a GL context loss).
    // Comparing against fbRealWidth/Height is what decides between a full glTexImage2D
    // (allocate) and a partial glTexSubImage2D (update) in the texture upload.
    int texWidth;
    int texHeight;

    // Bounding box, in framebuffer pixels, of the rectangles the server has written since
    // the last texture upload; empty while dirtyX2<=dirtyX1. Only meaningful together with
    // texWidth/texHeight (a full re-allocation ignores and then clears it). All four, the
    // texture size and the framebuffer itself share one lifetime, so all are read and
    // written under `mutex`.
    int dirtyX1, dirtyY1, dirtyX2, dirtyY2;

    // Protects modification to framebuffer & cursor
    MUTEX(mutex);
};

const int ClientExTag = 1;

ClientEx *getClientExtension(rfbClient *client) {
    return (ClientEx *) rfbClientGetClientData(client, (void *) &ClientExTag);
}

void setClientExtension(rfbClient *client, ClientEx *ex) {
    rfbClientSetClientData(client, (void *) &ClientExTag, ex);
}

/**
 * Returns reference to managed `RfbClientNative` associated with given rfbClient.
 */
jobject getManagedClient(rfbClient *client) {
    return getClientExtension(client)->managedClient;
}

/**
 * Associate given rfbClient & managed `RfbClientNative`.
 */
void setManagedClient(rfbClient *client, jobject managedClient) {
    getClientExtension(client)->managedClient = managedClient;
}

/**
 * Create new ClientEx and assign it to given client.
 */
ClientEx *assignClientExtension(rfbClient *client) {
    auto ex = (ClientEx *) malloc(sizeof(ClientEx));
    if (ex) {
        // Zeroed before anything reads it: managedClient is handed straight to
        // CallVoidMethod by the callbacks, and an uninitialized jobject is a crash
        // waiting for the first server message. fbRealWidth/Height are read by the
        // texture upload for the same reason.
        memset(ex, 0, sizeof(ClientEx));
        INIT_MUTEX(ex->mutex);
        ex->cursor = nullptr;
        setClientExtension(client, ex);
    }
    return ex;
}

/**
 * Free all resources related to client extension.
 */
void freeClientExtension(rfbClient *client) {
    auto ex = getClientExtension(client);
    if (ex) {
        TINI_MUTEX(ex->mutex);
        freeCursor(ex->cursor);
        free(ex);
        setClientExtension(client, nullptr);
    }
}

#endif //AVNC_CLIENTEX_H
