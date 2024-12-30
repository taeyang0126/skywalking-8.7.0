package com.lei.learn.sw.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * HelloController
 * </p>
 *
 * @author 伍磊
 */
@RestController
public class HelloController {

    ExecutorService executors = Executors.newFixedThreadPool(10);

    @GetMapping("/hello")
    public String hello() throws ExecutionException, InterruptedException {
        boolean flag = ThreadLocalRandom.current().nextInt(10) == 1;
        if (flag) {
            TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(10, 100));
            throw new RuntimeException("error!");
        }
        if (ThreadLocalRandom.current().nextInt(10) < 5) {
            Future<String> future = executors.submit(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(30, 40));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "hello: " + UUID.randomUUID();
            });
            return future.get();
        } else {
            TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(20, 50));
            return "hello: " + UUID.randomUUID();
        }
    }

}
