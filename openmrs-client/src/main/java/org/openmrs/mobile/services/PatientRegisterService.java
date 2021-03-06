package org.openmrs.mobile.services;

import android.app.IntentService;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.Intent;
import android.widget.Toast;

import org.jdeferred.DoneCallback;
import org.jdeferred.android.AndroidDeferredManager;
import org.jdeferred.multiple.MultipleResults;
import org.openmrs.mobile.api.RestApi;
import org.openmrs.mobile.api.ServiceGenerator;
import org.openmrs.mobile.api.SimpleDeferredObject;
import org.openmrs.mobile.api.SimplePromise;
import org.openmrs.mobile.application.OpenMRS;
import org.openmrs.mobile.databases.PatientRegistrationCache;
import org.openmrs.mobile.models.retrofit.IdGenPatientIdentifiers;
import org.openmrs.mobile.models.retrofit.Patient;
import org.openmrs.mobile.models.retrofit.PatientIdentifier;
import org.openmrs.mobile.models.retrofit.Resource;
import org.openmrs.mobile.models.retrofit.Results;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class PatientRegisterService extends IntentService {
    protected final OpenMRS mOpenMRS = OpenMRS.getInstance();
    PatientRegistrationCache cache=new PatientRegistrationCache(PatientRegisterService.this);
    Patient patient;
    List<Patient> patientList;

    public PatientRegisterService() {
        super("Register Patients");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        final List<Patient> tbd=new ArrayList<>();
        if(isNetworkAvailable()) {
            patientList=cache.getPatientList();
            final ListIterator<Patient> it = patientList.listIterator();
            while (it.hasNext()) {
                patient=it.next();
                if(patient.getSynced()==false) {
                    AndroidDeferredManager dm = new AndroidDeferredManager();
                    dm.when(getLocationUuid(), getIdGenPatientIdentifier(), getPatientIdentifierTypeUuid())
                            .done(new DoneCallback<MultipleResults>() {
                                @Override
                                public void onDone(MultipleResults results) {
                                    List<PatientIdentifier> identifiers = new ArrayList<>();

                                    PatientIdentifier identifier = new PatientIdentifier();
                                    identifier.setLocation((String) results.get(0).getResult());
                                    identifier.setIdentifier((String) results.get(1).getResult());
                                    identifier.setIdentifierType((String) results.get(2).getResult());
                                    identifiers.add(identifier);

                                    patient.setIdentifiers(identifiers);

                                    RestApi apiService =
                                            ServiceGenerator.createService(RestApi.class);
                                    Call<Patient> call = apiService.createPatient(patient);
                                    call.enqueue(new Callback<Patient>() {
                                        @Override
                                        public void onResponse(Call<Patient> call, Response<Patient> response) {
                                            Patient newPatient = response.body();
                                            Toast.makeText(PatientRegisterService.this, "Patient created with UUID " + newPatient.getUuid()
                                                    , Toast.LENGTH_SHORT).show();
                                            patient.setSynced(true);
                                            cache.setPatientList(patientList);

                                        }

                                        @Override
                                        public void onFailure(Call<Patient> call, Throwable t) {
                                            Toast.makeText(PatientRegisterService.this, t.toString(), Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                            });
                }

            }
        }
        else {
            Toast.makeText(PatientRegisterService.this, "No internet connection. Patient Registration data is saved locally " +
                    "and will sync when internet connection is restored. ", Toast.LENGTH_SHORT).show();
        }


    }


    SimplePromise<String> getLocationUuid() {
        final SimpleDeferredObject<String> deferred = new SimpleDeferredObject<>();

        RestApi apiService =
                ServiceGenerator.createService(RestApi.class);
        Call<Results<Resource>> call = apiService.getLocations();
        call.enqueue(new Callback<Results<Resource>>() {
            @Override
            public void onResponse(Call<Results<Resource>> call, Response<Results<Resource>> response) {
                Results<Resource> locationList = response.body();
                for (Resource result : locationList.getResults()) {
                    if ((result.getDisplay().trim()).equalsIgnoreCase((mOpenMRS.getLocation().trim()))) {
                        String locationUuid = result.getUuid();
                        deferred.resolve(locationUuid);
                    }
                }
            }

            @Override
            public void onFailure(Call<Results<Resource>> call, Throwable t) {
                Toast.makeText(PatientRegisterService.this,t.toString(),Toast.LENGTH_SHORT).show();
                deferred.reject(t);
            }

        });

        return deferred.promise();
    }

    SimplePromise<String> getIdGenPatientIdentifier() {
        final SimpleDeferredObject<String> deferred = new SimpleDeferredObject<>();

        String IDGEN_BASE_URL= mOpenMRS.getServerUrl()+'/';
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(IDGEN_BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        RestApi apiService =
                retrofit.create(RestApi.class);
        Call<IdGenPatientIdentifiers> call = apiService.getPatientIdentifiers(mOpenMRS.getUsername(),mOpenMRS.getPassword());
        call.enqueue(new Callback<IdGenPatientIdentifiers>() {
            @Override
            public void onResponse(Call<IdGenPatientIdentifiers> call, Response<IdGenPatientIdentifiers> response) {
                IdGenPatientIdentifiers idList = response.body();
                deferred.resolve(idList.getIdentifiers().get(0));
            }

            @Override
            public void onFailure(Call<IdGenPatientIdentifiers> call, Throwable t) {
                Toast.makeText(PatientRegisterService.this,t.toString(),Toast.LENGTH_SHORT).show();
                deferred.reject(t);
            }

        });

        return deferred.promise();
    }


    SimplePromise<String> getPatientIdentifierTypeUuid() {
        final SimpleDeferredObject<String> deferred = new SimpleDeferredObject<>();

        RestApi apiService =
                ServiceGenerator.createService(RestApi.class);
        Call<Results<PatientIdentifier>> call = apiService.getIdentifierTypes();
        call.enqueue(new Callback<Results<PatientIdentifier>>() {
            @Override
            public void onResponse(Call<Results<PatientIdentifier>> call, Response<Results<PatientIdentifier>> response) {
                Results<PatientIdentifier> idresList = response.body();
                for (Resource result : idresList.getResults()) {
                    if(result.getDisplay().equals("OpenMRS ID")) {
                        deferred.resolve(result.getUuid());
                        return;
                    }
                }
            }

            @Override
            public void onFailure(Call<Results<PatientIdentifier>> call, Throwable t) {
                Toast.makeText(PatientRegisterService.this,t.toString(),Toast.LENGTH_SHORT).show();
                deferred.reject(t);
            }

        });

        return deferred.promise();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager
                = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }
}