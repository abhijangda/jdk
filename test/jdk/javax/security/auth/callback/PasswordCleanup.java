/*
 * Copyright (C) 2022 THL A29 Limited, a Tencent company. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 8284910
 * @summary Buffer clean in PasswordCallback
 */

import javax.security.auth.callback.PasswordCallback;
import java.util.Arrays;
import java.util.WeakHashMap;

public final class PasswordCleanup {
    private final static WeakHashMap<PasswordCallback, ?> weakHashMap =
            new WeakHashMap<>();

    public static void main(String[] args) throws Exception {
        // Test password clearing at finalization.
        clearAtCollection();

        // Test password clearing with the specific method.
        clearWithMethod();
    }

    private static void clearAtCollection() throws Exception {
        // Create an object
        PasswordCallback passwordCallback =
                new PasswordCallback("Password: ", false);
        passwordCallback.setPassword("ThisIsAPassword".toCharArray());

        weakHashMap.put(passwordCallback, null);
        passwordCallback = null;

        // Check the clearing
        checkClearing();
    }

    private static void clearWithMethod() throws Exception {
        // Create an object
        PasswordCallback passwordCallback =
                new PasswordCallback("Password: ", false);
        passwordCallback.setPassword("ThisIsAPassword".toCharArray());
        char[] originPassword = passwordCallback.getPassword();

        // Use password clear method.
        passwordCallback.clearPassword();

        // Check that the password is cleared.
        char[] clearedPassword = passwordCallback.getPassword();
        if (Arrays.equals(originPassword, clearedPassword)) {
            throw new RuntimeException(
                "PasswordCallback.clearPassword() does not clear passwords");
        }

        weakHashMap.put(passwordCallback, null);
        passwordCallback = null;

        // Check the clearing
        checkClearing();
    }

    private static void checkClearing() throws Exception {
        // Wait to trigger the cleanup.
        for (int i = 0; i < 10 && weakHashMap.size() != 0; i++) {
            System.gc();
            Thread.sleep(100);
        }

        // Check if the object has been collected.
        if (weakHashMap.size() > 0) {
            throw new RuntimeException(
                "PasswordCallback object is not released");
        }
    }
}

