/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.pages.main.conversation.views.row.message.views;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import com.waz.api.UpdateListener;
import com.waz.api.User;
import com.waz.zclient.R;
import com.waz.zclient.controllers.accentcolor.AccentColorObserver;
import com.waz.zclient.pages.main.conversation.views.MessageViewsContainer;
import com.waz.zclient.pages.main.conversation.views.row.message.MessageViewController;
import com.waz.zclient.pages.main.conversation.views.row.separator.Separator;
import com.waz.zclient.pages.main.participants.dialog.DialogLaunchMode;
import com.waz.zclient.ui.text.GlyphTextView;
import com.waz.zclient.ui.text.TypefaceTextView;
import com.waz.zclient.ui.utils.ColorUtils;
import com.waz.zclient.ui.utils.ResourceUtils;
import com.waz.zclient.ui.utils.TextViewUtils;
import com.waz.zclient.ui.utils.TypefaceUtils;
import com.waz.zclient.ui.views.EphemeralDotAnimationView;
import com.waz.zclient.utils.ViewUtils;

import java.util.Locale;


public class PingMessageViewController extends MessageViewController implements UpdateListener,
                                                                                AccentColorObserver,
                                                                                View.OnClickListener {
    public static final String TAG = PingMessageViewController.class.getName();

    private TypefaceTextView textViewMessage;
    private GlyphTextView glyphTextView;
    private EphemeralDotAnimationView ephemeralDotAnimationView;
    private Locale locale;

    private View view;
    private User user;

    private final Typeface originalTypeface;

    @SuppressLint("InflateParams")
    public PingMessageViewController(Context context, MessageViewsContainer messageViewsContainer) {
        super(context, messageViewsContainer);
        LayoutInflater inflater = LayoutInflater.from(context);
        view = inflater.inflate(R.layout.row_conversation_knock, null);

        textViewMessage = ViewUtils.getView(view, R.id.ttv__row_conversation__ping_message);
        textViewMessage.setOnLongClickListener(this);
        glyphTextView = ViewUtils.getView(view, R.id.gtv__knock_icon);
        glyphTextView.setOnLongClickListener(this);
        ephemeralDotAnimationView = ViewUtils.getView(view, R.id.edav__ephemeral_view);

        locale = context.getResources().getConfiguration().locale;
        originalTypeface = textViewMessage.getTypeface();
    }

    @Override
    protected void onSetMessage(Separator separator) {
        messageViewsContainer.getControllerFactory().getAccentColorController().addAccentColorObserver(this);
        user = message.getUser();
        user.addUpdateListener(this);
        message.addUpdateListener(this);
        ephemeralDotAnimationView.setMessage(message);

        updated();
    }

    @Override
    public View getView() {
        return view;
    }

    @Override
    public void updated() {
        textViewMessage.setText(getPingMessage());
        if (message.isEphemeral() && message.isExpired()) {
            Typeface redactedTypeface = TypefaceUtils.getTypeface(TypefaceUtils.getRedactedTypedaceName());
            textViewMessage.setTypeface(redactedTypeface);
            glyphTextView.setTypeface(redactedTypeface);
            int accent = messageViewsContainer.getControllerFactory().getAccentColorController().getColor();
            glyphTextView.setTextColor(accent);
        } else {
            final int textColor = user.getAccent().getColor();
            textViewMessage.setTypeface(originalTypeface);
            glyphTextView.setTypeface(TypefaceUtils.getTypeface(TypefaceUtils.getGlyphsTypefaceName()));
            glyphTextView.setTextColor(textColor);
            TextViewUtils.boldText(textViewMessage);
        }
    }

    private String getPingMessage() {
        final String pingMessage;
        if (user.isMe()) {
            pingMessage = context.getString(R.string.content__you_pinged);
        } else {
            pingMessage = context.getString(R.string.content__xxx_pinged, user.getDisplayName().toUpperCase(locale));
        }
        return pingMessage;
    }

    @Override
    public void recycle() {
        if (!messageViewsContainer.isTornDown()) {
            messageViewsContainer.getControllerFactory().getAccentColorController().removeAccentColorObserver(this);
        }
        if (user != null) {
            user.removeUpdateListener(this);
        }
        if (message != null) {
            message.removeUpdateListener(this);
        }
        ephemeralDotAnimationView.setMessage(null);
        user = null;
        super.recycle();
    }

    @Override
    public void onClick(View v) {
        if (messageViewsContainer.isTornDown()) {
            return;
        }
        messageViewsContainer.getControllerFactory()
                             .getConversationScreenController()
                             .setPopoverLaunchedMode(DialogLaunchMode.AVATAR);
        if (!messageViewsContainer.isPhone()) {
            messageViewsContainer.getControllerFactory()
                                 .getPickUserController()
                                 .showUserProfile(user, glyphTextView);
        } else {
            messageViewsContainer.getControllerFactory()
                                 .getConversationScreenController()
                                 .showUser(user);
        }
    }

    @Override
    public void onAccentColorHasChanged(Object sender, int color) {
        ephemeralDotAnimationView.setPrimaryColor(color);
        ephemeralDotAnimationView.setSecondaryColor(ColorUtils.injectAlpha(ResourceUtils.getResourceFloat(context.getResources(), R.dimen.ephemeral__accent__timer_alpha),
                                                                           color));

        if (message != null &&
            message.isEphemeral() &&
            message.isExpired()) {
            glyphTextView.setTextColor(color);
        }
    }
}
