package com.liskovsoft.smartyoutubetv2.tv.ui.search.tags;

import android.os.Bundle;
import android.view.KeyEvent;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;

public class SearchTagsActivity extends LeanbackActivity {
    private SearchTagsFragment mFragment;
    private boolean mDownPressed;
    private boolean mWasPlayingBeforeSearch;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_search_tags);
        mFragment = (SearchTagsFragment) getSupportFragmentManager()
                .findFragmentById(R.id.search_tags_fragment);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // YouTok: When returning to search, don't auto-resume; keep paused
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Not needed here — LeanbackActivity.onStop handles pause for browse activities
    }

    @Override
    protected void onStart() {
        super.onStart();
        // YouTok: Pause the background video when Search opens
        PlaybackPresenter playback = PlaybackPresenter.instance(this);
        if (playback != null && playback.isPlaying()) {
            mWasPlayingBeforeSearch = true;
            playback.onPauseClicked();
        } else {
            mWasPlayingBeforeSearch = false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // If there are no results found, press the left key to reselect the microphone
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !mFragment.hasResults()) {
            mFragment.focusOnSearchField();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void finishReally() {
        super.finishReally();

        mFragment.onFinish();

        // YouTok: Resume playback when leaving Search if video was playing before
        if (mWasPlayingBeforeSearch) {
            PlaybackPresenter playback = PlaybackPresenter.instance(this);
            if (playback != null && !playback.isPlaying()) {
                playback.onPlayClicked();
            }
        }
    }
}
