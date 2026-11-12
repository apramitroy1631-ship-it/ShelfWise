package com.shelfwise.core.review;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.review.ReviewDtos.CorrectRequest;
import com.shelfwise.core.review.ReviewDtos.RejectRequest;
import com.shelfwise.core.review.ReviewDtos.ReviewItem;
import com.shelfwise.core.review.ReviewDtos.ReviewList;

import reactor.core.publisher.Mono;

/** Admin only: the review queue for imported orders that need a decision (D10). */
@RestController
@RequestMapping("/api/admin/reviews")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    /** GET /api/admin/reviews?state=WAITING|DECIDED&storeId=S01 */
    @GetMapping
    public Mono<ReviewList> list(@AuthenticationPrincipal UserAccount user,
                                 @RequestParam(required = false) String state,
                                 @RequestParam(required = false) String storeId) {
        return reviews.list(user, state, storeId);
    }

    @GetMapping("/{orderId}")
    public Mono<ReviewItem> get(@AuthenticationPrincipal UserAccount user, @PathVariable String orderId) {
        return reviews.get(user, orderId);
    }

    @PostMapping("/{orderId}/correct")
    public Mono<ReviewItem> correct(@AuthenticationPrincipal UserAccount user, @PathVariable String orderId,
                                    @Validated @RequestBody CorrectRequest request) {
        return reviews.correct(user, orderId, request);
    }

    @PostMapping("/{orderId}/reject")
    public Mono<ReviewItem> reject(@AuthenticationPrincipal UserAccount user, @PathVariable String orderId,
                                   @Validated @RequestBody RejectRequest request) {
        return reviews.reject(user, orderId, request);
    }
}
