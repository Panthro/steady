package steady.steady;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Controller
public class SteadyApplicationController {

    private Set<String> greetings = Stream.of("Hey", "Howdy", "Hello", "Hi").collect(Collectors.toSet());

    @GetMapping("/greetings")
    public ResponseEntity<Set<String>> getGreetings() {
        return ResponseEntity.ok(greetings);
    }

    @GetMapping("/greetings/{name}")
    public ResponseEntity<Greeting> getGreeting(@PathVariable String name) {
        return ResponseEntity.ok(new Greeting(name, greetings.iterator().next()));
    }

    @GetMapping("/greetings/{greeting}/{name}")
    public ResponseEntity<Greeting> getGreeting(@PathVariable String greeting, @PathVariable String name) {
        return ResponseEntity.of(findGreeting(greeting).map(g -> new Greeting(name, g))
        );
    }

    private Optional<String> findGreeting(@PathVariable String greeting) {
        return greetings.stream().filter(greeting::equalsIgnoreCase).findAny();
    }


    @PostMapping("/post/greetings/")
    public ResponseEntity<Greeting> postGreeting(@RequestBody String name) {
        return ResponseEntity.ok(new Greeting(name, greetings.iterator().next()));
    }


}
