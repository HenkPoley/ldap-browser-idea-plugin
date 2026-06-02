package org.majki.intellij.ldapbrowser.ldap.ui;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.ui.Messages;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class LdapErrorHandler {

    private static final String NOTIFICATION_GROUP = "LDAP notifications";

    private LdapErrorHandler() {
        throw new UnsupportedOperationException();
    }

    public static void handleError(String message) {
        handleError((String) null, message);
    }

    public static void handleError(Exception e, String message) {
        StringWriter stringWriter = new StringWriter();
        try (PrintWriter writer = new PrintWriter(stringWriter)) {
            e.printStackTrace(writer);
            writer.flush();
            handleError(stringWriter.toString(), message + ": " + exceptionSummary(e));
        }
    }

    private static String exceptionSummary(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }

        String detail = rootCause.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = throwable.getMessage();
        }
        if (detail == null || detail.trim().isEmpty()) {
            return rootCause.getClass().getSimpleName();
        }
        return rootCause.getClass().getSimpleName() + " - " + detail;
    }

    public static void handleError(String stackTrace, String message) {
        if (stackTrace == null) {
            Notifications.Bus.notify(new Notification(NOTIFICATION_GROUP, "LDAP Error", message, NotificationType.ERROR));
        } else {
            Notifications.Bus.notify(new Notification(NOTIFICATION_GROUP, "LDAP Exception",
                message + " (<a href=\"#showException\">show exception</a>)", NotificationType.ERROR,
                (notification, hyperlinkEvent) -> {
                    if ("#showException".equals(hyperlinkEvent.getDescription())) {
                        Messages.showErrorDialog(stackTrace, "LDAP Exception");
                    }
                }));
        }
    }

}
