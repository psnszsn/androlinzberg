/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.webkit;

import android.annotation.Nullable;
import android.text.TextUtils;

import java.util.regex.Pattern;

import libcore.content.type.MimeMap;

/** Two-way mapping between MIME types and file extensions. */
public class MimeTypeMap {
    private static final MimeTypeMap sMimeTypeMap = new MimeTypeMap();

    private MimeTypeMap() {}

    public static String getFileExtensionFromUrl(String url) {
        if (!TextUtils.isEmpty(url)) {
            int fragment = url.lastIndexOf('#');
            if (fragment > 0) {
                url = url.substring(0, fragment);
            }

            int query = url.lastIndexOf('?');
            if (query > 0) {
                url = url.substring(0, query);
            }

            int filenamePos = url.lastIndexOf('/');
            String filename = filenamePos >= 0 ? url.substring(filenamePos + 1) : url;
            if (!filename.isEmpty()
                    && Pattern.matches("[a-zA-Z_0-9\\.\\-\\(\\)\\%]+", filename)) {
                int dotPos = filename.lastIndexOf('.');
                if (dotPos >= 0) {
                    return filename.substring(dotPos + 1);
                }
            }
        }
        return "";
    }

    public boolean hasMimeType(String mimeType) {
        return MimeMap.getDefault().hasMimeType(mimeType);
    }

    @Nullable
    public String getMimeTypeFromExtension(String extension) {
        return MimeMap.getDefault().guessMimeTypeFromExtension(extension);
    }

    // Called by Android's native WebView glue.
    @SuppressWarnings("unused")
    private static String mimeTypeFromExtension(String extension) {
        return MimeMap.getDefault().guessMimeTypeFromExtension(extension);
    }

    public boolean hasExtension(String extension) {
        return MimeMap.getDefault().hasExtension(extension);
    }

    @Nullable
    public String getExtensionFromMimeType(String mimeType) {
        return MimeMap.getDefault().guessExtensionFromMimeType(mimeType);
    }

    public static MimeTypeMap getSingleton() {
        return sMimeTypeMap;
    }
}
