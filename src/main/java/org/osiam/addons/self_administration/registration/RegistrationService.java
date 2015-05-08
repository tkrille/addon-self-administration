/*
 * Copyright (C) 2013 tarent AG
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.osiam.addons.self_administration.registration;

import java.util.*;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

import org.joda.time.Duration;
import org.osiam.addons.self_administration.exception.InvalidAttributeException;
import org.osiam.addons.self_administration.service.ConnectorBuilder;
import org.osiam.addons.self_administration.template.RenderAndSendEmail;
import org.osiam.addons.self_administration.util.OneTimeToken;
import org.osiam.addons.self_administration.util.SelfAdministrationHelper;
import org.osiam.client.OsiamConnector;
import org.osiam.client.oauth.AccessToken;
import org.osiam.client.query.Query;
import org.osiam.client.query.QueryBuilder;
import org.osiam.resources.helper.SCIMHelper;
import org.osiam.resources.scim.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.common.base.Optional;
import com.google.common.base.Strings;

@Service
public class RegistrationService {

    @Inject
    private ConnectorBuilder connectorBuilder;

    @Inject
    private RenderAndSendEmail renderAndSendEmailService;

    @Value("${org.osiam.mail.from}")
    private String fromAddress;

    @Value("${org.osiam.scim.extension.urn}")
    private String internalScimExtensionUrn;

    @Value("${org.osiam.scim.extension.field.activationtoken}")
    private String activationTokenField;

    @Value("#{T(org.osiam.addons.self_administration.util.SelfAdministrationHelper).makeDuration(" +
            "\"${org.osiam.addon-self-administration.registration.activation-token-timeout:24h}\")}")
    private Duration activationTokenTimeout;

    private String[] allowedFields;
    private String[] allAllowedFields;

    @Value("${org.osiam.html.form.extensions:}")
    private String[] extensions;

    @Value("${org.osiam.html.form.usernameEqualsEmail:true}")
    private boolean usernameEqualsEmail;

    @Value("${org.osiam.html.form.password.length:8}")
    private int passwordLength;

    private boolean confirmPasswordRequired = true;

    @Value("${org.osiam.html.form.fields:}")
    private void setAllowedFields(String[] allowedFields) {
        List<String> trimmedFields = new ArrayList<>();

        for (String field : allowedFields) {
            trimmedFields.add(field.trim());
        }

        if (!trimmedFields.contains("email")) {
            trimmedFields.add("email");
        }

        if (!trimmedFields.contains("password")) {
            trimmedFields.add("password");
        }

        if (!trimmedFields.contains("confirmPassword")) {
            confirmPasswordRequired = false;
        }

        String fieldUserName = "userName";
        if (!usernameEqualsEmail && !trimmedFields.contains(fieldUserName)) {
            trimmedFields.add(fieldUserName);
        } else if (usernameEqualsEmail && trimmedFields.contains(fieldUserName)) {
            trimmedFields.remove(fieldUserName);
        }
        this.allowedFields = trimmedFields.toArray(new String[trimmedFields.size()]);
    }

    public boolean isUsernameIsAlreadyTaken(String userName) {
        Query query = new QueryBuilder().filter("userName eq \"" + userName + "\"").build();

        OsiamConnector osiamConnector = connectorBuilder.createConnector();
        AccessToken accessToken = osiamConnector.retrieveAccessToken();
        SCIMSearchResult<User> queryResult = osiamConnector.searchUsers(query, accessToken);
        return queryResult.getTotalResults() != 0L;
    }

    public User saveRegistrationUser(final User user) {
        Extension extension = new Extension.Builder(internalScimExtensionUrn)
                .setField(activationTokenField, new OneTimeToken().toString())
                .build();

        List<Role> roles = new ArrayList<Role>();
        Role role = new Role.Builder().setValue("USER").build();
        roles.add(role);

        User registrationUser = new User.Builder(user)
                .setActive(false)
                .addRoles(roles)
                .addExtension(extension)
                .build();

        OsiamConnector osiamConnector = connectorBuilder.createConnector();
        AccessToken accessToken = osiamConnector.retrieveAccessToken();
        return osiamConnector.createUser(registrationUser, accessToken);
    }

    public void sendRegistrationEmail(User user, HttpServletRequest request) {
        Optional<Email> email = SCIMHelper.getPrimaryOrFirstEmail(user);
        if (!email.isPresent()) {
            String message = "Could not register user. No email of user " + user.getUserName() + " found!";
            throw new InvalidAttributeException(message, "registration.exception.noEmail");
        }

        StringBuffer requestURL = request.getRequestURL().append("/activation");

        final OneTimeToken activationToken = OneTimeToken.fromString(user.getExtension(internalScimExtensionUrn)
                .getFieldAsString(activationTokenField));

        String registrationLink = SelfAdministrationHelper.createLinkForEmail(requestURL.toString(), user.getId(),
                "activationToken", activationToken.getToken());

        Map<String, Object> mailVariables = new HashMap<>();
        mailVariables.put("registrationLink", registrationLink);
        mailVariables.put("user", user);

        Locale locale = SelfAdministrationHelper.getLocale(user.getLocale());

        renderAndSendEmailService.renderAndSendEmail("registration", fromAddress, email.get().getValue(), locale,
                mailVariables);
    }

    public User activateUser(String userId, String activationTokenToCheck) {
        if (Strings.isNullOrEmpty(userId)) {
            throw new InvalidAttributeException("Can't confirm the user. The userId is empty", "activation.exception");
        }
        if (Strings.isNullOrEmpty(activationTokenToCheck)) {
            throw new InvalidAttributeException("Can't confirm the user " + userId + ". The activation token is empty",
                    "activation.exception");
        }

        OsiamConnector osiamConnector = connectorBuilder.createConnector();
        AccessToken accessToken = osiamConnector.retrieveAccessToken();
        User user = osiamConnector.getUser(userId, accessToken);

        if (user.isActive()) {
            return user;
        }

        Extension extension = user.getExtension(internalScimExtensionUrn);

        final OneTimeToken storedActivationToken = OneTimeToken
                .fromString(extension.getFieldAsString(activationTokenField));

        if (storedActivationToken.isExpired(activationTokenTimeout)) {
            UpdateUser updateUser = new UpdateUser.Builder()
                    .deleteExtensionField(extension.getUrn(), activationTokenField)
                    .build();
            osiamConnector.updateUser(userId, updateUser, accessToken);

            throw new InvalidAttributeException("Activation token is expired", "activation.exception");
        }

        if (!storedActivationToken.getToken().equals(activationTokenToCheck)) {
            throw new InvalidAttributeException(String.format("Activation token mismatch. Given: %s stored: %s",
                    activationTokenToCheck, storedActivationToken.getToken()),
                    "activation.exception");
        }

        UpdateUser updateUser = new UpdateUser.Builder()
                .deleteExtensionField(extension.getUrn(), activationTokenField)
                .updateActive(true)
                .build();

        return osiamConnector.updateUser(userId, updateUser, accessToken);
    }

    public String[] getAllAllowedFields() {
        if (allAllowedFields == null || allAllowedFields.length == 0) {
            List<String> allFields = new ArrayList<>();

            for (String field : allowedFields) {
                allFields.add(field.trim());
            }
            for (String field : extensions) {
                allFields.add(field.trim());
            }

            this.allAllowedFields = allFields.toArray(new String[allFields.size()]);
        }
        return allAllowedFields.clone();
    }

    public int getPasswordLength() {
        return passwordLength;
    }

    public boolean isUsernameEqualsEmail() {
        return usernameEqualsEmail;
    }

    public boolean isConfirmPasswordRequired() {
        return confirmPasswordRequired;
    }
}
