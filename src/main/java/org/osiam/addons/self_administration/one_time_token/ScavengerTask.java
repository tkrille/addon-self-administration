package org.osiam.addons.self_administration.one_time_token;

import com.google.common.base.Strings;
import org.osiam.addons.self_administration.service.OsiamService;
import org.osiam.client.exception.ConnectionInitializationException;
import org.osiam.client.exception.OsiamClientException;
import org.osiam.client.query.Query;
import org.osiam.client.query.QueryBuilder;
import org.osiam.resources.scim.UpdateUser;
import org.osiam.resources.scim.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ScavengerTask implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(ScavengerTask.class);

    private TaskScheduler taskScheduler;
    private final OsiamService osiamService;
    private final long timeout;
    private final String urn;
    private final String tokenField;
    private final String[] fieldsToDelete;

    public ScavengerTask(final TaskScheduler taskScheduler, final OsiamService osiamService, final long timeout,
                         final String urn, final String tokenField, final String... fieldsToDelete) {

        this.taskScheduler = taskScheduler;
        this.osiamService = osiamService;
        this.timeout = timeout;
        this.urn = urn;
        this.tokenField = tokenField;
        this.fieldsToDelete = fieldsToDelete.clone();
    }

    @Override
    public void run() {
        final Query query = new QueryBuilder()
                .filter(urn + "." + tokenField + " pr")
                .count(Integer.MAX_VALUE)
                .build();

        final List<User> users;
        try {
            users = osiamService.searchUsers(query).getResources();
        } catch (ConnectionInitializationException e) {
            LOG.warn("Failed to search for users ({}): {}", query, e.getMessage());
            return;
        }

        for (User user : users) {
            final OneTimeToken storedConfirmationToken = OneTimeToken.fromString(
                    user.getExtension(urn).getFieldAsString(tokenField));

            if (storedConfirmationToken.isExpired(timeout)) {

                final UpdateUser.Builder builder = new UpdateUser.Builder();

                builder.deleteExtensionField(urn, tokenField);

                for (String fieldToDelete : fieldsToDelete) {
                    if (!Strings.isNullOrEmpty(fieldToDelete)) {
                        builder.deleteExtensionField(urn, fieldToDelete);
                    }
                }

                final UpdateUser updateUser = builder.build();

                try {
                    osiamService.updateUser(user.getId(), updateUser);
                } catch (OsiamClientException e) {
                    LOG.warn("Failed to update user {}: {}", user.getId(), e.getMessage());
                    // let it fail and try again next time
                    continue;
                }
            }
        }
    }

    public void start() {
        final Date startTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
        taskScheduler.scheduleWithFixedDelay(this, startTime, timeout);
    }
}
