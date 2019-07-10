package com.androiddesdecero.arquitecturamvvm.repository;

import androidx.arch.core.util.Function;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.androiddesdecero.arquitecturamvvm.AppExecutors;
import com.androiddesdecero.arquitecturamvvm.api.ApiResponse;
import com.androiddesdecero.arquitecturamvvm.api.WebServiceApi;
import com.androiddesdecero.arquitecturamvvm.db.GitHubDb;
import com.androiddesdecero.arquitecturamvvm.db.RepoDao;
import com.androiddesdecero.arquitecturamvvm.model.Contributor;
import com.androiddesdecero.arquitecturamvvm.model.Repo;
import com.androiddesdecero.arquitecturamvvm.model.RepoSearchResponse;
import com.androiddesdecero.arquitecturamvvm.model.RepoSearchResult;
import com.androiddesdecero.arquitecturamvvm.utils.AbsentLiveData;
import com.androiddesdecero.arquitecturamvvm.utils.RateLimiter;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class RepoRepository {

    private final GitHubDb db;
    private final RepoDao repoDao;
    private final WebServiceApi githubService;
    private final AppExecutors appExecutors;

    private RateLimiter<String> repoListRateLimit = new RateLimiter<>(10, TimeUnit.MINUTES);

    @Inject
    public RepoRepository(AppExecutors appExecutors, GitHubDb db, RepoDao repoDao, WebServiceApi githubService){
        this.db = db;
        this.repoDao = repoDao;
        this.githubService = githubService;
        this.appExecutors = appExecutors;
    }

    public LiveData<Resource<List<Repo>>> loadRepos(String owner){
        return new NetworkBoundResource<List<Repo>, List<Repo>>(appExecutors){

            @Override
            protected boolean shouldFetch(List<Repo> data) {
                return data == null || data.isEmpty() || repoListRateLimit.shouldFetch(owner);
            }

            @Override
            protected LiveData<List<Repo>> loadFromDb() {
                return repoDao.loadRepositories(owner);
            }

            @Override
            protected void saveCallResult(List<Repo> item) {
                repoDao.insertRepos(item);
            }

            @Override
            protected LiveData<ApiResponse<List<Repo>>> createCall() {
                return githubService.getRepos(owner);
            }

            @Override
            protected void onFetchFailed(){
                repoListRateLimit.reset(owner);
            }
        }.asLiveData();

    }

    public LiveData<Resource<Repo>> loadRepo(String owner, String name){
        return new NetworkBoundResource<Repo, Repo>(appExecutors){

            @Override
            protected boolean shouldFetch(Repo data) {
                return data == null;
            }

            @Override
            protected LiveData<Repo> loadFromDb() {
                return repoDao.load(owner, name);
            }

            @Override
            protected void saveCallResult(Repo item) {
                repoDao.insert(item);
            }

            @Override
            protected LiveData<ApiResponse<Repo>> createCall() {
                return githubService.getRepo(owner, name);
            }
        }.asLiveData();
    }

    public LiveData<Resource<List<Contributor>>> loadContributors(String owner, String name){
        return new NetworkBoundResource<List<Contributor>, List<Contributor>>(appExecutors){

            @Override
            protected boolean shouldFetch(List<Contributor> data) {
                return data == null || data.isEmpty();
            }

            @Override
            protected LiveData<List<Contributor>> loadFromDb() {
                return repoDao.loadContributors(owner, name);
            }

            @Override
            protected void saveCallResult(List<Contributor> contributors) {
                for(Contributor contributor: contributors){
                    contributor.setRepoName(name);
                    contributor.setRepoOwner(owner);
                }
                db.beginTransaction();
                try{
                    repoDao.creatreRepoIfNotExists(new Repo(Repo.UNKNOWN_ID, name, owner + "/" +name,
                            "", 0, new Repo.Owner(owner, null)));
                    repoDao.insertContributors(contributors);
                    db.setTransactionSuccessful();
                }finally {
                    db.endTransaction();
                }
            }

            @Override
            protected LiveData<ApiResponse<List<Contributor>>> createCall() {
                return githubService.getContributors(owner, name);
            }
        }.asLiveData();
    }

    public LiveData<Resource<Boolean>> searchNextPage(String query){
        FetchNextSearchPageTask fetchNextSearchPageTask = new FetchNextSearchPageTask(
                query, githubService, db);
        appExecutors.networkIO().execute(fetchNextSearchPageTask);
        return fetchNextSearchPageTask.getLiveData();
    }

    public LiveData<Resource<List<Repo>>> search (String query){
        return new NetworkBoundResource<List<Repo>, RepoSearchResponse>(appExecutors){

            @Override
            protected boolean shouldFetch(List<Repo> data) {
                return data == null;
            }

            @Override
            protected LiveData<List<Repo>> loadFromDb() {
                return Transformations.switchMap(repoDao.search(query), new Function<RepoSearchResult, LiveData<List<Repo>>>() {
                    @Override
                    public LiveData<List<Repo>> apply(RepoSearchResult searchData) {
                        if(searchData == null){
                            return AbsentLiveData.create();
                        }else {
                            return repoDao.loadOrdered(searchData.repoIds);
                        }
                    }
                });
            }

            @Override
            protected void saveCallResult(RepoSearchResponse item) {
                List<Integer> repoIds = item.getRepoIds();
                RepoSearchResult repoSearchResult = new RepoSearchResult(
                        query, repoIds, item.getTotal(), item.getNextPage());
                db.beginTransaction();
                try{
                    repoDao.insertRepos(item.getItems());
                    repoDao.insert(repoSearchResult);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }

            @Override
            protected LiveData<ApiResponse<RepoSearchResponse>> createCall() {
                return githubService.searchRepos(query);
            }

            @Override
            protected RepoSearchResponse processResponse(ApiResponse<RepoSearchResponse> response) {
                RepoSearchResponse body = response.body;
                if(body != null){
                    body.setNextPage(response.getNextPage());
                }
                return body;
            }
        }.asLiveData();
    }
 }
