package ru.nsu.fit.vectorserver;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.nsu.fit.vectorserver.dto.*;

import java.util.List;


@RestController
@RequestMapping("/vectors")
public class VectorController {
    private final VectorService vectorService;

    public VectorController(VectorService vectorService) {
        this.vectorService = vectorService;
    }

    @PostMapping
    public ResponseEntity<VectorResponse> saveVector(@RequestBody AddRequest request) {
        VectorObject savedObject = vectorService.save(request);
        VectorResponse response = new VectorResponse(
                request.id(), savedObject.getVector(), savedObject.getUrl()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<VectorResponse> getVector(@PathVariable Long id) {
        VectorObject object = vectorService.get(id);
        VectorResponse response = new VectorResponse(
                id, object.getVector(), object.getUrl()
        );
        if (object == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/search")
    public ResponseEntity<List<VectorResponse>> search(@RequestBody SearchRequest request){

    }
}