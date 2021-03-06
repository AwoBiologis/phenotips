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
package org.phenotips.measurements.internal;

import org.phenotips.Constants;
import org.phenotips.data.Patient;
import org.phenotips.data.events.PatientChangingEvent;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;

import java.util.Date;
import java.util.List;

import javax.inject.Named;
import javax.inject.Singleton;

import org.joda.time.DateTime;
import org.joda.time.Days;

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.DateProperty;
import com.xpn.xwiki.objects.NumberProperty;

/**
 * Update the Age property whenever the birth date or measurement date properties are changed.
 *
 * @version $Id$
 */
@Component
@Named("measurement-age-updater")
@Singleton
public class MeasurementAgeUpdater extends AbstractEventListener
{
    /** The XClass used for storing measurements data. */
    private static final EntityReference CLASS_REFERENCE = new EntityReference("MeasurementsClass",
        EntityType.DOCUMENT, Constants.CODE_SPACE_REFERENCE);

    /** The name of the XProperty holding the age at the time of measurement, which will be updated by this listener. */
    private static final String AGE_PROPERTY_NAME = "age";

    /** The name of the XProperty holding the date when the measurement occurred. */
    private static final String DATE_PROPERTY_NAME = "date";

    /** Default constructor, sets up the listener name and the list of events to subscribe to. */
    public MeasurementAgeUpdater()
    {
        super("measurement-age-updater", new PatientChangingEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        XWikiDocument doc = (XWikiDocument) source;

        BaseObject patientRecordObj = doc.getXObject(Patient.CLASS_REFERENCE);
        if (patientRecordObj == null) {
            return;
        }
        Date birthDate = patientRecordObj.getDateValue("date_of_birth");

        List<BaseObject> objects = doc.getXObjects(CLASS_REFERENCE);
        if (objects == null || objects.isEmpty()) {
            return;
        }
        for (BaseObject measurement : objects) {
            try {
                if (measurement == null) {
                    continue;
                } else if ("birth".equals(measurement.getStringValue("type"))) {
                    measurement.setFloatValue(AGE_PROPERTY_NAME, 0);
                    ((DateProperty) measurement.get(DATE_PROPERTY_NAME)).setValue(null);
                    return;
                }
                Date measurementDate = measurement.getDateValue(DATE_PROPERTY_NAME);
                if (measurementDate == null || birthDate == null) {
                    ((NumberProperty) measurement.get(AGE_PROPERTY_NAME)).setValue(null);
                } else {
                    measurement.setFloatValue(AGE_PROPERTY_NAME,
                        Days.daysBetween(new DateTime(birthDate), new DateTime(measurementDate)).getDays() / 30.4375f);
                }
            } catch (XWikiException e) {
                // Not expected
            }
            continue;
        }
    }
}
