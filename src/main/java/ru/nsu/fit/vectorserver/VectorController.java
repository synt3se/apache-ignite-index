package ru.nsu.fit.vectorserver;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.nsu.fit.vectorserver.dto.*;

import java.util.Map;

@RestController
@RequestMapping("/vectors")
public class VectorController {
    private final VectorService vectorService;

    public VectorController(VectorService vectorService) {
        this.vectorService = vectorService;
    }

    @PostMapping
    public ResponseEntity<Map.Entry<Long, VectorObject>> saveVector(@RequestBody AddRequest request) {
        Map.Entry<Long, VectorObject> savedObject = vectorService.save(request);
        return ResponseEntity.ok(savedObject); //TODO vector response
    }

    @GetMapping("/{id}")
    public ResponseEntity<VectorObject> getVector(@PathVariable Long id) {
        VectorObject object = vectorService.get(id);
        //TODO vector response
        if (object == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(object);
    }

    @PostMapping("/search")
    public void search(@RequestBody AddRequest request){

    }
}