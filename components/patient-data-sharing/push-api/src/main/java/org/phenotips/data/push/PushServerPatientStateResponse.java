/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/
 */

package org.phenotips.data.push;

import org.phenotips.data.Consent;

import org.xwiki.component.annotation.Role;
import org.xwiki.stability.Unstable;

import java.util.List;

import net.sf.json.JSON;

/**
 * API for responses concerning the status of a patient record, such as consents.
 *
 * @version $Id$
 * @since 1.0M11
 */
@Unstable
@Role
public interface PushServerPatientStateResponse extends PushServerResponse
{
    /** Will return null if the response is an error */
    List<Consent> getConsents();

    /**
     * When working with consents, it is most convenient for them to stay in the form of JSON.
     * @return JSON containing consents, as generated by the server or {@code null} if an error has occurred
     */
    JSON getConsentsAsJson();
}
