package ru.maipomogator.bot.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.InlineQuery;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.BaseRequest;
import com.pengrad.telegrambot.response.BaseResponse;

import lombok.extern.log4j.Log4j2;
import ru.maipomogator.bot.processors.callback.CallbackProcessor;
import ru.maipomogator.bot.processors.callback.DefaultCallbackProcessor;
import ru.maipomogator.bot.processors.inline.DefaultInlineProcessor;
import ru.maipomogator.bot.processors.inline.InlineProcessor;
import ru.maipomogator.bot.processors.message.DefaultMessageProcessor;
import ru.maipomogator.bot.processors.message.MessageProcessor;

@Log4j2
@Component
public class MainUpdatesListener implements UpdatesListener {

    private final TelegramBot bot;

    private final List<MessageProcessor> messageProcessors;
    private final DefaultMessageProcessor dmp;
    private final List<CallbackProcessor> callbackProcessors;
    private final DefaultCallbackProcessor dcp;

    private List<InlineProcessor> inlineProcessors;
    private DefaultInlineProcessor dip;

    public MainUpdatesListener(TelegramBot bot,
            List<MessageProcessor> messageProcessors, DefaultMessageProcessor dmp,
            List<CallbackProcessor> callbackProcessors, DefaultCallbackProcessor dcp,
            List<InlineProcessor> inlineProcessors, DefaultInlineProcessor dip) {
        this.bot = bot;

        messageProcessors.remove(dmp);
        this.messageProcessors = messageProcessors;
        this.dmp = dmp;

        callbackProcessors.remove(dcp);
        this.callbackProcessors = callbackProcessors;
        this.dcp = dcp;

        inlineProcessors.remove(dip);
        this.inlineProcessors = inlineProcessors;
        this.dip = dip;
    }

    @Override
    public int process(List<Update> updates) {
        for (Update update : updates) {
            List<BaseRequest<?, ? extends BaseResponse>> requests = new ArrayList<>(10);
            if (hasTextMessage(update)) {
                Message message = update.message();
                log.info("Processing message update from {} ({})", message.from().username(), message.from().id());
                requests.addAll(processMessage(message));
            } else if (hasCallbackQuery(update)) {
                CallbackQuery callback = update.callbackQuery();
                log.info("Processing callback update from {} ({})", callback.from().username(), callback.from().id());
                requests.addAll(processCallbackQuery(callback));
            } else if (hasInlineQuery(update)) {
                InlineQuery query = update.inlineQuery();
                log.info("Processing inline update from {} ({})", query.from().username(), query.from().id());
                requests.addAll(processInlineQuery(query));
            } else {
                log.info("Received update is not supported. Skipping.");
            }
            for (BaseRequest<?, ? extends BaseResponse> request : requests) {
                BaseResponse response = bot.execute(request);
                if (!response.isOk()) {
                    log.error(response.description());
                }
            }
        }
        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private List<BaseRequest<?, ? extends BaseResponse>> processInlineQuery(InlineQuery query) {
        List<BaseRequest<?, ? extends BaseResponse>> requests = new ArrayList<>();
        boolean isProcessed = false;
        for (InlineProcessor processor : inlineProcessors) {
            if (processor.applies(query.query())) {
                requests.addAll(processor.process(query));
                isProcessed = true;
                break;
            }
        }
        if (!isProcessed)
            requests.addAll(dip.process(query));

        return requests;
    }

    private List<BaseRequest<?, ? extends BaseResponse>> processMessage(Message message) {
        List<BaseRequest<?, ? extends BaseResponse>> requests = new ArrayList<>();
        boolean isProcessed = false;
        for (MessageProcessor processor : messageProcessors) {
            if (processor.applies(message.text())) {
                requests.addAll(processor.process(message));
                isProcessed = true;
                break;
            }
        }
        if (!isProcessed)
            requests.addAll(dmp.process(message));

        return requests;
    }

    private List<BaseRequest<?, ? extends BaseResponse>> processCallbackQuery(CallbackQuery callback) {
        List<BaseRequest<?, ? extends BaseResponse>> requests = new ArrayList<>();
        boolean isProcessed = false;
        for (CallbackProcessor processor : callbackProcessors) {
            if (processor.applies(callback.data())) {
                requests.addAll(processor.process(callback));
                isProcessed = true;
                break;
            }
        }
        if (!isProcessed)
            requests.addAll(dcp.process(callback));

        return requests;
    }

    private boolean hasTextMessage(Update update) {
        return update.message() != null && update.message().text() != null && update.message().viaBot() == null;
    }

    private boolean hasCallbackQuery(Update update) {
        return update.callbackQuery() != null;
    }

    private boolean hasInlineQuery(Update update) {
        return update.inlineQuery() != null;
    }
}
