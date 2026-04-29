package org.example;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.example.proto.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TaskDistributorImpl extends TaskDistributorGrpc.TaskDistributorImplBase {

    private final ManagedChannel formatterChannel;
    private final TaskFormatterGrpc.TaskFormatterBlockingStub formatterStub;

    private final Map<String, UniversalTask> workerTasks = new ConcurrentHashMap<>();

    public TaskDistributorImpl() {
        this("localhost", 8081);
    }

    public TaskDistributorImpl(String formatterHost, int formatterPort) {
        formatterChannel = ManagedChannelBuilder.forAddress(formatterHost, formatterPort)
                .usePlaintext()
                .build();
        formatterStub = TaskFormatterGrpc.newBlockingStub(formatterChannel);
    }

    @Override
    public void requestSubtask(DistributorRequest request,
                               StreamObserver<UniversalTask> responseObserver) {
        System.out.println("Запрос от вычислятора: " + request.getWorkerId());


        SubtaskResponse response = formatterStub.getNextSubtask(Empty.newBuilder().build());

        if (response.getHasTask()) {

            UniversalTask data = UniversalTask.newBuilder()
                    .setHasTask(true)
                    .setTaskNumber(response.getTaskNumber())
                    .setTaskKind(response.getTaskKind())
                    .setTaskPayload(response.getTaskPayload())
                    .build();


            workerTasks.put(request.getWorkerId(), data);

            responseObserver.onNext(data);
        } else {
            responseObserver.onNext(UniversalTask.newBuilder().setHasTask(false).build());
        }

        responseObserver.onCompleted();
    }

    @Override
    public void submitWorkerResult(UniversalResult request, StreamObserver<ResultResponse> responseObserver) {
        ResultRequest formatterRequest = ResultRequest.newBuilder()
                .setTaskNumber(request.getTaskNumber())
                .setResultLabel(request.getResultLabel())
                .setScore(request.getScore())
                .setResultPayload(request.getResultPayload())
                .build();

        ResultResponse result = formatterStub.submitResult(formatterRequest);
        responseObserver.onNext(result);
        responseObserver.onCompleted();
    }

    @Override
    public void sendTaskToWorker(TaskAssignment request,
                                 StreamObserver<AssignmentResponse> responseObserver) {
        System.out.println("Отправляем задачу вычислятору " + request.getWorkerId());


        AssignmentResponse response = AssignmentResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Задача назначена")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Server server = ServerBuilder.forPort(8082)
                .addService(new TaskDistributorImpl())
                .build()
                .start();

        System.out.println("Распределятор подзадач запущен на порту 8082");
        System.out.println("Подключен к формирователю на порту 8081");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Останавливаем сервер...");
            server.shutdown();
        }));

        server.awaitTermination();
    }
}