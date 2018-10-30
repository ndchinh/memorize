package cloud.techstar.memorize.words;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;

import com.orhanobut.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import cloud.techstar.memorize.R;
import cloud.techstar.memorize.database.Words;
import cloud.techstar.memorize.database.WordsDataSource;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static cloud.techstar.memorize.utils.MemorizeUtils.getNowTime;
import static com.google.common.base.Preconditions.checkNotNull;

public class WordsPresenter implements WordsContract.Presenter, WordsDataSource.LoadWordsCallback {

    @NonNull
    private final WordsDataSource wordRepository;

    @NonNull
    private final WordsContract.View wordsView;

    private WordFilterType currentFilterType = WordFilterType.ACTIVE_WORDS;

    private Handler jishoHandler;
    private OkHttpClient jishoClient;
    private List<Words> searchWords;


    public WordsPresenter(@NonNull WordsDataSource wordRepository, @NonNull WordsContract.View wordsView) {
        this.wordRepository = wordRepository;
        this.wordsView = wordsView;
        jishoHandler = new Handler(Looper.getMainLooper());
        jishoClient = new OkHttpClient();
        searchWords = new ArrayList<>();
        wordsView.setPresenter(this);
    }

    @Override
    public void init() {
        loadWords(false);
    }

    @Override
    public void onWordsLoaded(List<Words> words) {

    }

    @Override
    public void onDataNotAvailable() {

    }

    @Override
    public void result(int requestCode, int resultCode) {

    }

    @Override
    public void loadWords(boolean forceUpdate) {
        loadWords(forceUpdate, true);
    }

    /**
     * @param forceUpdate   Pass in true to refresh the data in the {@link WordsDataSource}
     * @param showLoadingUI Pass in true to display a loading icon in the UI
     */
    private void loadWords(boolean forceUpdate, final boolean showLoadingUI) {
        if (showLoadingUI) {
            wordsView.setLoadingIndicator(true);
        }
        if (forceUpdate) {
            wordRepository.refreshWords();
        }

        wordRepository.getWords(new WordsDataSource.LoadWordsCallback() {
            @Override
            public void onWordsLoaded(List<Words> words) {

                searchWords = words;

                List<Words> mainWords = new ArrayList<Words>();

                Logger.e("Presenter words count : "+words.size());

                for (Words word : words) {

                    switch (getFilterType()) {
                        case ALL_WORDS:
                            mainWords.add(word);
                            break;
                        case ACTIVE_WORDS:
                            if (!word.isFavorite() && !word.isMemorize()) {
                                mainWords.add(word);
                            }
                            break;
                        default:
                            if (!word.isFavorite() && !word.isMemorize()) {
                                mainWords.add(word);
                            }
                            break;
                    }
                }

                if (currentFilterType == WordFilterType.RECENTLY){
                    Collections.sort(mainWords, new Comparator<Words>() {
                        @Override
                        public int compare(Words o1, Words o2) {
                            return o2.getCreated().compareTo(o1.getCreated());
                        }
                    });
                }
                Logger.e("Presenter words count : "+words.size());

                wordsView.showWords(mainWords);

                if (showLoadingUI) {
                    wordsView.setLoadingIndicator(false);
                }
            }

            @Override
            public void onDataNotAvailable() {
                wordsView.showLoadingWordsError();
            }
        });

    }

    @Override
    public void openWordDetails(@NonNull Words requestedWord) {
        checkNotNull(requestedWord, "requestedWord cannot be null!");
        saveWord(requestedWord);
        wordsView.showWordDetail(requestedWord);
    }

    @Override
    public void saveWord(Words word){
        wordRepository.saveWord(word);
    }

    @Override
    public void search(String keyWord) {

        wordsView.setLoadingIndicator(true);

        List<Words> result = new ArrayList<>();
        for (Words word : searchWords) {
            if (word.getCharacter().contains(keyWord))
                result.add(word);
        }

        if (result.size() > 0){
            wordsView.showWords(result);

            wordsView.setLoadingIndicator(false);
        } else {
            searchRemote(keyWord);
        }
    }

    @Override
    public void searchRemote(String keyWord) {

        final List<Words> apiWords = new ArrayList<>();

        final Request jishoRequest = new Request.Builder()
                .url("https://jisho.org/api/v1/search/words?keyword="+keyWord)
                .build();

        jishoClient.newCall(jishoRequest).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {

                wordsView.setLoadingIndicator(false);
            }

            @Override
            public void onResponse(Call call, final Response response) throws IOException {

                final String res = response.body().string();
                jishoHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject ob = new JSONObject(res);
                            Logger.d(ob);

                            JSONArray datas = ob.getJSONArray("data");

                            for (int i = 0; i < datas.length(); i++) {

                                JSONObject data = datas.getJSONObject(i);

                                JSONArray tag = data.getJSONArray("tags");
                                JSONArray japanese = data.getJSONArray("japanese");

                                final List<String> levelList = new ArrayList<>();

                                for (int t = 0; t< tag.length(); t++) {
                                    levelList.add(tag.getString(t));
                                }

                                String kanji = japanese.getJSONObject(0).getString("word");
                                String character = japanese.getJSONObject(0).getString("reading");

                                JSONArray senses = data.getJSONArray("senses");

                                List<String> meaningList = new ArrayList<>();
                                List<String> partOfSpeechList = new ArrayList<>();


                                JSONObject sObject = senses.getJSONObject(0);
                                JSONArray english = sObject.getJSONArray("english_definitions");
                                JSONArray partOfSpeech = sObject.getJSONArray("parts_of_speech");

                                for (int e = 0; e< english.length(); e++) {
                                    meaningList.add(english.getString(e));
                                }
                                for (int p = 0; p< partOfSpeech.length(); p++) {
                                    partOfSpeechList.add(partOfSpeech.getString(p));
                                }
                                Words word = new Words(UUID.randomUUID().toString(), character, meaningList, null, kanji, partOfSpeechList, levelList, getNowTime());
                                apiWords.add(word);
                            }

                            wordsView.showWords(apiWords);

                            wordsView.setLoadingIndicator(false);
                        } catch (JSONException e) {
                            e.printStackTrace();
                            wordsView.setLoadingIndicator(false);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void setFilterType(WordFilterType filterType) {
        currentFilterType = filterType;
    }

    @Override
    public WordFilterType getFilterType() {
        return currentFilterType;
    }

    @Override
    public void setViewType(WordViewType viewType) {

    }

    @Override
    public WordViewType getViewType() {
        return null;
    }
}
