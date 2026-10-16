/*
 * Copyright © 2017-2024  Kynetics, Inc.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.hara.ddiclient.api

import java.io.File

/**
 * Provides the directory used by the client
 * to store the artifacts downloaded during the
 * updates.
 *
 * Each client needs its own directory: before a download the client
 * removes the artifacts that the current update doesn't need, including
 * those of any other client using the same directory. Files not created
 * by the client are left alone.
 */
interface DirectoryForArtifactsProvider {

    /**
     * If it throws, the client closes the current action as failed.
     * @return the base directory used to store
     * the artifacts
     */
    fun directoryForArtifacts(): File
}
