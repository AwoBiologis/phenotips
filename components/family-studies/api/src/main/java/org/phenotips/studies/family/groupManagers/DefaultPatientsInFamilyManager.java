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
package org.phenotips.studies.family.groupManagers;

import org.phenotips.data.Patient;
import org.phenotips.data.PatientRepository;
import org.phenotips.entities.spi.AbstractOutgoingPrimaryEntityConnectionsManager;
import org.phenotips.security.authorization.AuthorizationService;
import org.phenotips.studies.family.Family;
import org.phenotips.studies.family.FamilyRepository;
import org.phenotips.studies.family.PatientsInFamilyManager;
import org.phenotips.studies.family.Pedigree;
import org.phenotips.studies.family.PedigreeProcessor;
import org.phenotips.studies.family.exceptions.PTException;
import org.phenotips.studies.family.exceptions.PTInternalErrorException;
import org.phenotips.studies.family.exceptions.PTInvalidFamilyIdException;
import org.phenotips.studies.family.exceptions.PTInvalidPatientIdException;
import org.phenotips.studies.family.exceptions.PTNotEnoughPermissionsOnFamilyException;
import org.phenotips.studies.family.exceptions.PTNotEnoughPermissionsOnPatientException;
import org.phenotips.studies.family.exceptions.PTPatientAlreadyInAnotherFamilyException;
import org.phenotips.studies.family.exceptions.PTPatientNotInFamilyException;
import org.phenotips.studies.family.exceptions.PTPedigreeContainesSamePatientMultipleTimesException;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.util.DefaultParameterizedType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.security.authorization.Right;
import org.xwiki.users.User;
import org.xwiki.users.UserManager;

import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * @version $Id$
 */
@Component(roles = PatientsInFamilyManager.class)
@Named("Family:Patient")
@Singleton
public class DefaultPatientsInFamilyManager
    extends AbstractOutgoingPrimaryEntityConnectionsManager<Family, Patient>
    implements PatientsInFamilyManager
{
    /** Type instance for lookup. */
    public static final ParameterizedType TYPE = new DefaultParameterizedType(null, PatientsInFamilyManager.class,
        Family.class, Patient.class);

    @Inject
    private UserManager userManager;

    @Inject
    private FamilyRepository familyRepository;

    @Inject
    private PatientRepository patientRepository;

    @Inject
    private PedigreeProcessor pedigreeConverter;

    @Inject
    private AuthorizationService authorizationService;

    /**
     * Public constructor.
     */
    @Override
    public void initialize()
    {
        super.subjectsManager = this.familyRepository;
        super.objectsManager = this.patientRepository;
    }

    @Override
    public boolean connect(Family family, Patient patient) throws PTException
    {
        return this.connectAll(family, Arrays.asList(patient));
    }

    @Override
    public boolean disconnect(Family family, Patient patient)
    {
        return this.disconnectAll(family, Arrays.asList(patient));
    }

    @Override
    public boolean connectAll(Family family, Collection<Patient> patients) throws PTException
    {
        return this.addAllMembers(family, patients, this.userManager.getCurrentUser());
    }

    @Override
    public boolean disconnectAll(Family family, Collection<Patient> patients)
    {
        return this.removeAllMembers(family, patients, this.userManager.getCurrentUser());
    }

    /*
     * Adds members to the family.
     *
     * Note: the synchronization could be limited to addAllMembersInternal and updateFamilyPermissionsAndSave, because
     * checkValidity is read-only.
     *
     * @param family family which should get a new member
     * @param patients to add to family
     * @param updatingUser right checks are done for this user
     * @throws PTException in case addition was not successful for any reason (not enough rights, patient already has a
     *             family, etc.)
     * @return true if successful
     */
    private synchronized boolean addAllMembers(Family family, Collection<Patient> patients, User updatingUser)
        throws PTException
    {
        this.checkValidity(family, patients, updatingUser);
        this.addAllMembersInternal(family, patients);

        return true;
    }

    /*
     * Removes all given patients from the family.
     *
     * See note about synchronization in addAllMembers(Family, Collection, User).
     *
     * @param family family which should lose a new member
     * @param patients to remove from family
     * @param updatingUser right checks are done for this user
     * @throws PTException if removal was not successful for any reason (not enough rights, patient not a member of
     *             this family, etc.)
     * @return true if successful
     */
    private synchronized boolean removeAllMembers(Family family, Collection<Patient> patients, User updatingUser)
        throws PTException
    {
        this.checkIfPatientsCanBeRemovedFromFamily(family, patients, updatingUser);
        this.removeAllMembersInternal(family, patients);

        return true;
    }

    @Override
    public boolean forceRemoveAllMembers(Family family)
    {
        return this.forceRemoveAllMembers(family, this.userManager.getCurrentUser());
    }

    @Override
    public boolean forceRemoveAllMembers(Family family, User updatingUser)
    {
        if (!this.authorizationService.hasAccess(updatingUser, Right.EDIT, family.getDocumentReference())) {
            return false;
        }
        try {
            this.removeAllMembers(family, this.getAllConnections(family), updatingUser);
            // Remove the members without updating family document since we don't care about it as it will
            // be removed anyway

            return true;
        } catch (PTException ex) {
            this.logger.error("Failed to unlink all patients for the family [{}]: {}", family.getId(), ex.getMessage());
            return false;
        }
    }

    @Override
    public void setPedigree(Family family, Pedigree pedigree) throws PTException
    {
        this.setPedigree(family, pedigree, this.userManager.getCurrentUser());
    }

    @Override
    public synchronized void setPedigree(Family family, Pedigree pedigree, User updatingUser) throws PTException
    {
        // note: whenever available, internal versions of helper methods are used which modify the
        // family document but do not save it to disk
        Collection<Patient> oldMembers = this.getAllConnections(family);

        Collection<Patient> currentMembers = new ArrayList<>();
        for (String id : pedigree.extractIds()) {
            currentMembers.add(this.patientRepository.get(id));
        }

        // Add new members to family
        List<Patient> patientsToAdd = new LinkedList<>();
        patientsToAdd.addAll(currentMembers);
        patientsToAdd.removeAll(oldMembers);

        this.checkValidity(family, patientsToAdd, updatingUser);
        this.addAllMembersInternal(family, patientsToAdd);

        // update patient data from pedigree's JSON
        // (no links to families are set at this point, only patient dat ais updated)
        this.updatePatientsFromJson(pedigree, updatingUser);

        boolean firstPedigree = (family.getPedigree() == null);

        XWikiContext context = this.xcontextProvider.get();

        this.setPedigreeObject(family, pedigree, context);

        // Removed members who are no longer in the family
        List<Patient> patientsToRemove = new LinkedList<>();
        patientsToRemove.addAll(oldMembers);
        patientsToRemove.removeAll(currentMembers);

        this.checkIfPatientsCanBeRemovedFromFamily(family, patientsToRemove, updatingUser);
        this.disconnectAll(family, patientsToRemove);

        if (firstPedigree && StringUtils.isEmpty(family.getExternalId())) {
            // default family identifier to proband last name - only on first pedigree creation
            // and only if no extrenal id is already (manully) defined
            String lastName = pedigree.getProbandPatientLastName();
            if (lastName != null) {
                this.setFamilyExternalId(lastName, family, context);
            }
        }
    }

    private boolean setPedigreeObject(Family family, Pedigree pedigree, XWikiContext context) {
        if (pedigree == null) {
            this.logger.error("Can not set NULL pedigree for family [{}]", family.getId());
            return false;
        }

        BaseObject pedigreeObject = family.getXDocument().getXObject(Pedigree.CLASS_REFERENCE);
        pedigreeObject.set(Pedigree.IMAGE, ((pedigree == null) ? "" : pedigree.getImage(null)), context);
        pedigreeObject.set(Pedigree.DATA, ((pedigree == null) ? "" : pedigree.getData().toString()), context);

        // update proband ID every time pedigree is changed
        BaseObject familyClassObject = family.getXDocument().getXObject(Family.CLASS_REFERENCE);
        if (familyClassObject != null) {
            String probandId = pedigree.getProbandId();
            if (!StringUtils.isEmpty(probandId)) {
                Patient patient = this.patientRepository.get(probandId);
                familyClassObject.setStringValue("proband_id",
                        (patient == null) ? "" : patient.getDocument().toString());
            } else {
                familyClassObject.setStringValue("proband_id", "");
            }
        }

        return true;
    }

    private void updatePatientsFromJson(Pedigree pedigree, User updatingUser)
    {
        String idKey = "id";
        try {
            List<JSONObject> patientsJson = this.pedigreeConverter.convert(pedigree);

            for (JSONObject singlePatient : patientsJson) {
                if (singlePatient.has(idKey)) {
                    Patient patient = this.patientRepository.get(singlePatient.getString(idKey));
                    if (!this.authorizationService.hasAccess(updatingUser, Right.EDIT, patient.getDocument())) {
                        // skip patients the current user does not have edit rights for
                        continue;
                    }
                    patient.updateFromJSON(singlePatient);
                }
            }
        } catch (Exception ex) {
            throw new PTInternalErrorException();
        }
    }

    private void checkValidity(Family family, Collection<Patient> newMembers, User updatingUser) throws PTException
    {
        // Checks that current user has edit permissions on family
        if (!this.authorizationService.hasAccess(updatingUser, Right.EDIT, family.getDocumentReference())) {
            throw new PTNotEnoughPermissionsOnFamilyException(Right.EDIT, family.getId());
        }

        Patient duplicatePatient = this.findDuplicate(newMembers);
        if (duplicatePatient != null) {
            throw new PTPedigreeContainesSamePatientMultipleTimesException(duplicatePatient.getId());
        }

        // Check if every new member can be added to the family
        if (newMembers != null) {
            for (Patient patient : newMembers) {
                checkIfPatientCanBeAddedToFamily(family, patient, updatingUser);
            }
        }
    }

    private synchronized boolean saveFamilyDocument(Family family, String documentHistoryComment, XWikiContext context)
    {
        try {
            context.getWiki().saveDocument(family.getXDocument(), documentHistoryComment, context);
        } catch (XWikiException e) {
            this.logger.error("Error saving family [{}] document for commit {}: [{}]",
                family.getId(), documentHistoryComment, e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * This method may be called either as a standalone invocation, or
     * internally as part of family pedigree update.
     * ({@link #checkValidity(Family, List, User)} and family saving is always
     * done outside of this method.
     *
     * Updating permissions is an expensive operation which takes all patients
     * into account, so it shouldn't be done after adding each patient. It
     * should be done by the calling code.
     *
     * Note: This method is not synchronized. At the time of writing (refactoring) this code, this method is called
     * from the public and synchronized {@link #addAllMembers(Family, Collection, User)}
     * {@link #setPedigree(Family, Pedigree, User)}.
     */
    private void addAllMembersInternal(Family family, Collection<Patient> patients) throws PTException
    {
        if (family == null) {
            throw new PTInvalidFamilyIdException(null);
        }

        Collection<Patient> members = super.getAllConnections(family);

        for (Patient patient : patients) {
            if (patient == null) {
                throw new PTInvalidPatientIdException(null);
            }

            // TODO
            // if (patient.getXDocument() == null) {
            //     throw new PTInvalidPatientIdException(patient.getId());
            // }
            String patientId = patient.getId();
            XWikiDocument patientDocument = getDocument(patient);
            if (patientDocument == null) {
                throw new PTInvalidPatientIdException(patientId);
            }

            // Check if not already a member
            if (members.contains(patient)) {
                this.logger.error("Patient [{}] already a member of the same family, not adding", patientId);
                throw new PTPedigreeContainesSamePatientMultipleTimesException(patientId);
            }
        }

        if (!super.connectAll(family, patients)) {
            // TODO what if some members could not be added? rollback?
            // It can be implemented either here or in entities, for handling a more general case.
            throw new PTInternalErrorException();
        }
    }

    /*
     * Calls to {@link #checkIfPatientCanBeRemovedFromFamily} and {@link #updateFamilyPermissionsAndSave} before and
     * after, respectively, are the responsibility of the caller.
     *
     * See note about synchronization in addAllMembersInternal().
     */
    private void removeAllMembersInternal(Family family, Collection<Patient> patients) throws PTException
    {
        if (family == null) {
            throw new PTInvalidFamilyIdException(null);
        }

        Collection<Patient> members = this.getAllConnections(family);

        for (Patient patient : patients) {
            if (patient == null) {
                throw new PTInvalidPatientIdException(null);
            }

            // TODO
            // if (patient.getXDocument() == null) {
            // throw new PTInvalidPatientIdException(patientId);
            // }
            String patientId = patient.getId();
            XWikiContext context = this.xcontextProvider.get();
            XWikiDocument patientDocument = getDocument(patient);
            if (patientDocument == null) {
                throw new PTInvalidPatientIdException(patientId);
            }

            if (!members.contains(patient)) {
                this.logger.error("Can't remove patient [{}] from family [{}]: patient not a member of the family",
                        patientId, family.getId());
                throw new PTPatientNotInFamilyException(patientId);
            }

            // Remove patient from the pedigree
            Pedigree pedigree = family.getPedigree();
            if (pedigree != null) {
                pedigree.removeLink(patientId);
                if (!this.setPedigreeObject(family, pedigree, context)) {
                    this.logger.error("Could not remove patient [{}] from pedigree from the family [{}]", patientId,
                        family.getId());
                    throw new PTInternalErrorException();
                }
            }
        }

        if (!super.disconnectAll(family, patients)) {
            throw new PTInternalErrorException();
        }
    }

    private Patient findDuplicate(Collection<Patient> updatedMembers)
    {
        List<Patient> duplicationCheck = new LinkedList<>();
        duplicationCheck.addAll(updatedMembers);
        for (Patient member : updatedMembers) {
            duplicationCheck.remove(member);
            if (duplicationCheck.contains(member)) {
                return member;
            }
        }

        return null;
    }

    private void checkIfPatientCanBeAddedToFamily(Family family, Patient patient, User updatingUser)
            throws PTException {
        // check rights
        if (!this.authorizationService.hasAccess(updatingUser, Right.EDIT, family.getDocumentReference())) {
            throw new PTNotEnoughPermissionsOnFamilyException(Right.EDIT, family.getId());
        }
        if (!this.authorizationService.hasAccess(updatingUser, Right.EDIT, patient.getDocument())) {
            throw new PTNotEnoughPermissionsOnPatientException(Right.EDIT, patient.getId());
        }
        // check for logical problems: patient in another family
        Collection<Family> families = this.getAllReverseConnections(patient);
        if (families.size() == 1) {
            Family familyForLinkedPatient = families.iterator().next();
            if (familyForLinkedPatient != null && !familyForLinkedPatient.getId().equals(family.getId())) {
                throw new PTPatientAlreadyInAnotherFamilyException(patient.getId(), familyForLinkedPatient.getId());
            }
        }
    }

    private void setFamilyExternalId(String externalId, Family family, XWikiContext context) {
        BaseObject familyObject = family.getXDocument().getXObject(Family.CLASS_REFERENCE);
        familyObject.set("external_id", externalId, context);
    }

    private void checkIfPatientsCanBeRemovedFromFamily(Family family, Collection<Patient> patients, User updatingUser)
        throws PTException
    {
        for (Patient patient : patients) {
            // check rights
            if (!this.authorizationService.hasAccess(updatingUser, Right.EDIT, family.getDocumentReference())) {
                throw new PTNotEnoughPermissionsOnFamilyException(Right.EDIT, family.getId());
            }
            if (!this.authorizationService.hasAccess(updatingUser, Right.EDIT, patient.getDocument())) {
                throw new PTNotEnoughPermissionsOnPatientException(Right.EDIT, patient.getId());
            }
        }
    }

    private String allPatientsString(Collection<Patient> patients)
    {
        StringBuilder sb = new StringBuilder();
        for (Patient patient : patients) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(patient.getId());
        }
        sb.insert(0, "[");
        sb.append("]");
        return sb.toString();
    }

    // TODO remove when there's patient.getXDocument()
    private XWikiDocument getDocument(Patient patient)
    {
        try {
            DocumentReference document = patient.getDocument();
            XWikiDocument patientDocument = getDocument(document);
            return patientDocument;
        } catch (XWikiException ex) {
            this.logger.error("Can't get patient document for patient [{}]: []", patient.getId(), ex);
            return null;
        }
    }

    // TODO remove when there's patient.getXDocument()
    private XWikiDocument getDocument(EntityReference docRef) throws XWikiException
    {
        XWikiContext context = this.xcontextProvider.get();
        XWiki wiki = context.getWiki();
        return wiki.getDocument(docRef, context);
    }
}
