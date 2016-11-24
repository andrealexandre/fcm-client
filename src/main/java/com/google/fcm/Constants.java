package com.google.fcm;

/**
 * @author André Alexandre
 * @since 1.0.0
 */
class Constants {
    static final String GCM_SEND_ENDPOINT = "https://gcm-http.googleapis.com/gcm/send";
    static final String PARAM_TO = "to";
    static final String TOPIC_PREFIX = "/topics/";
    static final String PARAM_REGISTRATION_ID = "registration_id";
    static final String PARAM_COLLAPSE_KEY = "collapse_key";
    static final String PARAM_DELAY_WHILE_IDLE = "delay_while_idle";
    static final String PARAM_DRY_RUN = "dry_run";
    static final String PARAM_RESTRICTED_PACKAGE_NAME = "restricted_package_name";
    static final String PARAM_PAYLOAD_PREFIX = "data.";
    static final String PARAM_TIME_TO_LIVE = "time_to_live";
    static final String PARAM_PRIORITY = "priority";
    static final String PARAM_CONTENT_AVAILABLE = "content_available";
    static final String MESSAGE_PRIORITY_NORMAL = "normal";
    static final String MESSAGE_PRIORITY_HIGH = "high";
    static final String ERROR_QUOTA_EXCEEDED = "QuotaExceeded";
    static final String ERROR_DEVICE_QUOTA_EXCEEDED = "DeviceQuotaExceeded";
    static final String ERROR_MISSING_REGISTRATION = "MissingRegistration";
    static final String ERROR_INVALID_REGISTRATION = "InvalidRegistration";
    static final String ERROR_MISMATCH_SENDER_ID = "MismatchSenderId";
    static final String ERROR_NOT_REGISTERED = "NotRegistered";
    static final String ERROR_MESSAGE_TOO_BIG = "MessageTooBig";
    static final String ERROR_MISSING_COLLAPSE_KEY = "MissingCollapseKey";
    static final String ERROR_UNAVAILABLE = "Unavailable";
    static final String ERROR_INTERNAL_SERVER_ERROR = "InternalServerError";
    static final String ERROR_INVALID_TTL= "InvalidTtl";
    static final String TOKEN_MESSAGE_ID = "id";
    static final String TOKEN_CANONICAL_REG_ID = "registration_id";
    static final String TOKEN_ERROR = "Error";
    static final String JSON_REGISTRATION_IDS = "registration_ids";
    static final String JSON_TO = "to";
    static final String JSON_PAYLOAD = "data";
    static final String JSON_NOTIFICATION = "notification";
    static final String JSON_NOTIFICATION_TITLE = "title";
    static final String JSON_NOTIFICATION_BODY = "body";
    static final String JSON_NOTIFICATION_ICON = "icon";
    static final String JSON_NOTIFICATION_SOUND = "sound";
    static final String JSON_NOTIFICATION_BADGE = "badge";
    static final String JSON_NOTIFICATION_TAG = "tag";
    static final String JSON_NOTIFICATION_COLOR = "color";
    static final String JSON_NOTIFICATION_CLICK_ACTION = "click_action";
    static final String JSON_NOTIFICATION_BODY_LOC_KEY = "body_loc_key";
    static final String JSON_NOTIFICATION_BODY_LOC_ARGS = "body_loc_args";
    static final String JSON_NOTIFICATION_TITLE_LOC_KEY = "title_loc_key";
    static final String JSON_NOTIFICATION_TITLE_LOC_ARGS = "title_loc_args";
    static final String JSON_SUCCESS = "success";
    static final String JSON_FAILURE = "failure";
    static final String JSON_CANONICAL_IDS = "canonical_ids";
    static final String JSON_MULTICAST_ID = "multicast_id";
    static final String JSON_RESULTS = "results";
    static final String JSON_ERROR = "error";
    static final String JSON_MESSAGE_ID = "message_id";
}