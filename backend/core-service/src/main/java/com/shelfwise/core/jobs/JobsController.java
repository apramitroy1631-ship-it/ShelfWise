package com.shelfwise.core.jobs;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.jobs.ScheduledJobs.Job;
import com.shelfwise.core.jobs.ScheduledJobs.Run;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Admin only: see the latest job runs, and run a job now (e.g. in a demo). */
@RestController
@RequestMapping("/api/admin/jobs")
public class JobsController {

    private final ScheduledJobs jobs;

    public JobsController(ScheduledJobs jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/runs")
    public Flux<Run> runs(@AuthenticationPrincipal UserAccount user, @RequestParam(defaultValue = "20") int limit) {
        requireAdmin(user);
        return jobs.recent(Math.max(1, Math.min(limit, 100)));
    }

    /** POST /api/admin/jobs/EXPIRE_DRAFTS/run or /LOW_STOCK_RECOUNT/run */
    @PostMapping("/{job}/run")
    public Mono<Run> run(@AuthenticationPrincipal UserAccount user, @PathVariable String job) {
        requireAdmin(user);
        if (!ScheduledJobs.names().contains(job)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No job '" + job + "'; jobs: " + ScheduledJobs.names()));
        }
        return jobs.run(Job.valueOf(job), "MANUAL", user.id());
    }

    private static void requireAdmin(UserAccount user) {
        if (user.role() != UserAccount.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins run jobs");
        }
    }
}
