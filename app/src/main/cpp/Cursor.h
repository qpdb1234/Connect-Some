/*
 * Copyright (c) 2022  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

#ifndef AVNC_CURSOR_H
#define AVNC_CURSOR_H


/******************************************************************************
 * Some servers (e.g TigerVNC) may not send the cursor immediately after
 * connection. To provide consistent experience to users, we use a default
 * cursor as fallback.
 *****************************************************************************/

const uint16_t DefaultCursorWidth = 10;
const uint16_t DefaultCursorHeight = 16;
const uint16_t DefaultCursorXHot = 1;
const uint16_t DefaultCursorYHot = 1;

const uint32_t DefaultCursorBuffer[DefaultCursorWidth * DefaultCursorHeight]
        = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x00FFFFFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x00FFFFFF, 0x00FFFFFF, 0, 0, 0, 0,
           0, 0, 0, 0, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0, 0, 0, 0, 0, 0, 0, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF,
           0x00FFFFFF, 0, 0, 0, 0, 0, 0, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0, 0, 0, 0, 0,
           0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0, 0, 0, 0, 0x00FFFFFF, 0x00FFFFFF,
           0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0, 0, 0, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF,
           0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0, 0, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF,
           0x00FFFFFF, 0x00FFFFFF, 0, 0, 0, 0, 0, 0x00FFFFFF, 0x00FFFFFF, 0, 0x00FFFFFF, 0x00FFFFFF, 0, 0, 0, 0, 0,
           0x00FFFFFF, 0, 0, 0, 0x00FFFFFF, 0x00FFFFFF, 0, 0, 0, 0, 0, 0, 0, 0, 0x00FFFFFF, 0x00FFFFFF, 0, 0, 0, 0, 0,
           0, 0, 0, 0, 0x00FFFFFF, 0x00FFFFFF, 0, 0, 0, 0, 0, 0, 0, 0, 0x00FFFFFF, 0x00FFFFFF, 0, 0, 0, 0, 0, 0, 0, 0,
           0, 0, 0, 0};

const uint8_t DefaultCursorMask[DefaultCursorWidth * DefaultCursorHeight]
        = {1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 0,
           0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 1,
           1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0,
           0, 0, 1, 1, 1, 0, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0,
           0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0};


/******************************************************************************
 * Cursor management
 *****************************************************************************/

/**
 * Wrapper for cursor information.
 *
 * rfbClient struct does not maintain all cursor related information inside it.
 * Things like xHot, yHot are passed only via the cursor shape callback.
 * This wrapper holds all information necessary to render the cursor.
 */
struct Cursor {
    uint8_t *buffer;
    uint16_t width;
    uint16_t height;
    uint16_t xHot;
    uint16_t yHot;
};

//Only 4-byte pixels are currently supported
const uint8_t PixelBytes = 4;

/**
 * Largest cursor this client will hold, per edge.
 *
 * The pair arrives on the wire as two unsigned 16-bit numbers chosen by the
 * server, so the product has to be bounded before it is used as a size. No
 * real pointer is anywhere near this: the X cursor format caps a pixmap at
 * 32767, and a cursor bigger than the screen is a bug in whatever sent it.
 */
const uint16_t MaxCursorDimension = 1024;

void freeCursor(Cursor *cursor) {
    if (cursor) free(cursor->buffer);
    free(cursor);
}

/**
 * Updates a cursor with given pixel data.
 */
void updateCursor(Cursor *cursor, const uint8_t *buffer, const uint8_t *mask, const uint16_t width,
                  const uint16_t height, const uint16_t xHot, const uint16_t yHot) {
    if (!cursor)
        return;

    // The multiplication is done in size_t on purpose. `uint16_t * uint16_t` is
    // promoted to `int`, so a server claiming a 65535x65535 cursor overflows it -
    // which is undefined behaviour, and in practice a negative count that later
    // reappears as a huge size_t: the malloc fails, this function returns having
    // already freed the old pixels, and width/height keep describing a buffer that
    // no longer exists.
    if (width == 0 || height == 0 || width > MaxCursorDimension || height > MaxCursorDimension)
        return; // Keep the previous cursor rather than a half-updated one

    const size_t pixelCount = (size_t) width * (size_t) height;
    const size_t bufferSize = pixelCount * PixelBytes;

    auto newBuffer = (uint8_t *) malloc(bufferSize);
    if (!newBuffer)
        return;

    free(cursor->buffer);
    cursor->buffer = newBuffer;

    memcpy(cursor->buffer, buffer, bufferSize);
    cursor->width = width;
    cursor->height = height;
    cursor->xHot = xHot;
    cursor->yHot = yHot;

    // Set Alpha channel according to mask
    for (size_t i = 0; i < pixelCount; ++i) {
        if (mask[i])
            cursor->buffer[PixelBytes * i + 3] = 0xff; // Alpha => 1
        else
            cursor->buffer[PixelBytes * i + 3] = 0;    // Alpha => 0
    }
}

/**
 * Creates a new Cursor, initialized with default info.
 */
Cursor *newCursor() {
    auto cursor = (Cursor *) malloc(sizeof(Cursor));
    if (cursor) {
        memset(cursor, 0, sizeof(Cursor));
        updateCursor(cursor, reinterpret_cast<const uint8_t *>(DefaultCursorBuffer), DefaultCursorMask,
                     DefaultCursorWidth, DefaultCursorHeight, DefaultCursorXHot, DefaultCursorYHot);
    }
    return cursor;
}

#endif //AVNC_CURSOR_H