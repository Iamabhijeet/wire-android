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
package com.waz.zclient.startup.fragments;

import android.support.test.runner.AndroidJUnit4;

import com.waz.api.ZMessagingApi;
import com.waz.api.impl.Self;
import com.waz.zclient.R;
import com.waz.zclient.UsernamesTakeoverTestActivity;
import com.waz.zclient.core.stores.api.IZMessagingApiStore;
import com.waz.zclient.newreg.fragments.FirstTimeAssignUsernameFragment;
import com.waz.zclient.testutils.FragmentTest;
import com.waz.zclient.utils.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import static android.support.test.espresso.action.ViewActions.pressBack;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.withText;
import static junit.framework.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static org.mockito.Mockito.when;


@RunWith(AndroidJUnit4.class)
public class UsernamesTakeOverScreenTest extends FragmentTest<UsernamesTakeoverTestActivity> {
    public UsernamesTakeOverScreenTest() {
        super(UsernamesTakeoverTestActivity.class);
    }

    private final static String DEFAULT_DISPLAY_NAME = "Test User";
    private final static String DEFAULT_DISPLAY_USERNAME = "testuser";

    @Test
    public void verifyNotPossibleToSkip() throws InterruptedException {
        activity.setOnChooseUsernameChosenCalled(false);
        activity.setOnKeepUsernameCalled(false);
        attachFragment(FirstTimeAssignUsernameFragment.newInstance(DEFAULT_DISPLAY_NAME, DEFAULT_DISPLAY_USERNAME), FirstTimeAssignUsernameFragment.TAG);
        pressBack();
        Thread.sleep(500);
        assertEquals(activity.isOnChooseUsernameChosenCalled(), false);
        assertEquals(activity.isOnKeepUsernameCalled(), false);
        Thread.sleep(500);
    }

    @Test
    public void verifyConversationListIsOpenedAfterAcceptingUsername() throws InterruptedException {
        activity.setOnChooseUsernameChosenCalled(false);
        activity.setOnKeepUsernameCalled(false);
        attachFragment(FirstTimeAssignUsernameFragment.newInstance(DEFAULT_DISPLAY_NAME, DEFAULT_DISPLAY_USERNAME), FirstTimeAssignUsernameFragment.TAG);
        Thread.sleep(500);
        onView(withId(R.id.zb__username_first_assign__keep)).perform(click());
        Thread.sleep(500);
        assertEquals(activity.isOnKeepUsernameCalled(), true);
        Thread.sleep(500);
    }

    @Test
    public void verifyUserHasTakeOverScreenWithUsername() {
        activity.setOnChooseUsernameChosenCalled(false);
        activity.setOnKeepUsernameCalled(false);
        Self mockSelf = mock(Self.class);
        when(mockSelf.getName()).thenReturn(DEFAULT_DISPLAY_NAME);
        when(mockSelf.getUsername()).thenReturn("");
        IZMessagingApiStore mockZMessagingApiStore = activity.getStoreFactory().getZMessagingApiStore();
        ZMessagingApi mockZMessagingApi = mock(ZMessagingApi.class);
        when(mockZMessagingApiStore.getApi()).thenReturn(mockZMessagingApi);
        when(mockZMessagingApi.getSelf()).thenReturn(mockSelf);
        attachFragment(FirstTimeAssignUsernameFragment.newInstance(mockSelf.getName(), DEFAULT_DISPLAY_USERNAME), FirstTimeAssignUsernameFragment.TAG);

        onView(withId(R.id.ttv__name)).check(matches(withText(mockSelf.getName())));
        onView(withId(R.id.ttv__username)).check(matches(withText(StringUtils.formatUsername(DEFAULT_DISPLAY_USERNAME))));
    }
}
