package org.graylog2.plugins.slack.callback;

import com.floreysoft.jmte.Engine;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.MessageSummary;
import org.graylog2.plugin.alarms.AlertCondition;
import org.graylog2.plugin.alarms.callbacks.AlarmCallback;
import org.graylog2.plugin.alarms.callbacks.AlarmCallbackConfigurationException;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationException;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.streams.Stream;
import org.graylog2.plugins.slack.SlackClient;
import org.graylog2.plugins.slack.SlackMessage;
import org.graylog2.plugins.slack.SlackPluginBase;
import org.graylog2.plugins.slack.configuration.SlackConfiguration;
import org.graylog2.plugins.slack.configuration.SlackConfigurationRequestFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.base.Strings.isNullOrEmpty;

public class SlackAlarmCallback extends SlackPluginBase implements AlarmCallback {

    private final Engine templateEngine;

    @Inject
    public SlackAlarmCallback(Engine templateEngine) {
        this.templateEngine = templateEngine;
    }

    @Override
    public void initialize(final Configuration config) throws AlarmCallbackConfigurationException {
        try {
            super.setConfiguration(config);
        } catch (ConfigurationException e) {
            throw new AlarmCallbackConfigurationException("Configuration error. " + e.getMessage());
        }
    }

    @Override
    public void call(Stream stream, AlertCondition.CheckResult result) {
        final SlackClient client = new SlackClient(configuration);
        String text = buildFullMessageBody(stream, result);
        SlackMessage slackMessage = createSlackMessage(configuration, text);

        // Add custom message
        String template = configuration.getString(SlackConfiguration.CK_CUSTOM_MESSAGE);
        boolean hasTemplate = !isNullOrEmpty(template);
        if (hasTemplate) {
            String customMessage = buildCustomMessage(stream, result, template);
            slackMessage.setCustomMessage(customMessage);
        }

        // Add backlogItem message
        String backlogItemTemplate = configuration.getString(SlackConfiguration.CK_BACKLOG_ITEM_MESSAGE);
        boolean hasBacklogItemTemplate = !isNullOrEmpty(backlogItemTemplate);
        if(hasBacklogItemTemplate) {
            List<String> backlogItemMessages = buildBacklogItemMessages(stream, result, template);
            slackMessage.setBacklogItemMessages(backlogItemMessages);
        }

        try {
            client.send(slackMessage);
        } catch (SlackClient.SlackClientException e) {
            throw new RuntimeException("Could not send message to Slack.", e);
        }
    }

    private String buildFullMessageBody(Stream stream, AlertCondition.CheckResult result) {
        String graylogUri = configuration.getString(SlackConfiguration.CK_GRAYLOG2_URL);
        String titleLink;
        if (!isNullOrEmpty(graylogUri)) {
            titleLink = "<" + buildStreamLink(graylogUri, stream) + "|" + stream.getTitle() + ">";
        } else {
            titleLink = "_" + stream.getTitle() + "_";
        }

        // Build custom message
        boolean notifyChannel = configuration.getBoolean(SlackConfiguration.CK_NOTIFY_CHANNEL);
        String audience = notifyChannel ? "@channel " : "";
        String description = result.getResultDescription();
        return String.format("%s*Alert for Graylog stream %s*:\n> %s \n", audience, titleLink, description);
    }

    private String buildCustomMessage(Stream stream, AlertCondition.CheckResult result, String template) {
        List<Message> backlog = getAlarmBacklog(result);
        Map<String, Object> model = getCustomMessageModel(stream, result, backlog);
        try {
            return templateEngine.transform(template, model);
        } catch (Exception ex) {
            return ex.toString();
        }
    }

    private List<String> buildBacklogItemMessages(Stream stream, AlertCondition.CheckResult result, String template) {
        return getAlarmBacklog(result).stream()
                .map(backlogItem -> {
                    Map<String, Object> model = getBacklogItemModel(stream, backlogItem);
                    try {
                        return templateEngine.transform(template, model);
                    } catch (Exception ex) {
                        return ex.toString();
                    }
                }).collect(Collectors.toList());
    }

    private List<Message> getAlarmBacklog(AlertCondition.CheckResult result) {
        final AlertCondition alertCondition = result.getTriggeredCondition();
        final List<MessageSummary> matchingMessages = result.getMatchingMessages();
        final int effectiveBacklogSize = Math.min(alertCondition.getBacklog(), matchingMessages.size());

        if (effectiveBacklogSize == 0) return Collections.emptyList();
        final List<MessageSummary> backlogSummaries = matchingMessages.subList(0, effectiveBacklogSize);
        final List<Message> backlog = Lists.newArrayListWithCapacity(effectiveBacklogSize);
        for (MessageSummary messageSummary : backlogSummaries) {
            backlog.add(messageSummary.getRawMessage());
        }

        return backlog;
    }

    private Map<String, Object> getCustomMessageModel(Stream stream, AlertCondition.CheckResult result, List<Message> backlog) {
        Map<String, Object> model = new HashMap<>();
        String graylogUri = configuration.getString(SlackConfiguration.CK_GRAYLOG2_URL);
        model.put("stream", stream);
        model.put("check_result", result);
        model.put("alert_condition", result.getTriggeredCondition());
        model.put("backlog", backlog);
        model.put("backlog_size", backlog.size());
        if (!isNullOrEmpty(graylogUri)) {
            model.put("stream_url", buildStreamLink(graylogUri, stream));
        }

        return model;
    }

    private Map<String, Object> getBacklogItemModel(Stream stream, Message backlog_item) {
        Map<String, Object> model = new HashMap<>();
        String graylogUri = configuration.getString(SlackConfiguration.CK_GRAYLOG2_URL);
        model.put("stream", stream);
        model.put("backlog_item", backlog_item);
        if (!isNullOrEmpty(graylogUri)) {
            model.put("stream_url", buildStreamLink(graylogUri, stream));
        }

        return model;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return configuration.getSource();
    }

    @Override
    public void checkConfiguration() {
        /* Never actually called by graylog-server */
    }

    @Override
    public ConfigurationRequest getRequestedConfiguration() {
        return SlackConfigurationRequestFactory.createSlackAlarmCallbackConfigurationRequest();
    }

    @Override
    public String getName() {
        return "Slack Alarm Callback";
    }
}
