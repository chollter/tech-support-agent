package com.gcll.ticketagent.api;

import com.gcll.ticketagent.eval.EvalReport;
import com.gcll.ticketagent.eval.EvalRunner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evals")
public class EvalController {
    private final EvalRunner evalRunner;

    public EvalController(EvalRunner evalRunner) {
        this.evalRunner = evalRunner;
    }

    @PostMapping("/run")
    public EvalReport run() {
        return evalRunner.run();
    }
}
