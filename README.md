# clj-dribble

This project is a solution of test assignment.

You have to create a tool to calculate Dribbble stats:

    For a given Dribbble user find all followers
    For each follower find all shots
    For each shot find all "likers"
    Calculate Top10 "likers"

This solution strictly follows recomendation from Dribbble API documentations: 

'It is possible for some resources in the future to not be paginated based on page number, so it is important to follow these Link header values instead of constructing your own URLs". 

This raizes some challenges because the process of collecting resources from Dribbble API becomes inherently stateful and non-parallelizable. As an answer to this challenge such abstractions were introduced:

* StatefulProducer -  maintains its internal state (e.g. an URL to resources list for the next iteration). It is created with initial state and producer-function. Retrives the list of the results and the next state from the function, refreshes the internal state and returns results to the caller. Returns nil if there are no more results. Producer-function should return nil as the next state if no transitions to further states available.
* StatefulMultiProducer - sequentially takes a value from a source (typically a queue), creates a regular producer, takes a value from that producer until it returns nil. Then takes next value from the source etc. Contains current producer as its internal state and, transitively, the internal state of the producer. 
* Queue - an abstraction that defines a regular queue with take! and put! operations. This implementation is built on core/async, other possible implementations may use durable-queue etc.
* Pipeline - connects source (typically Queue or Producer) to destination (typically Queue). Loops through all values from source until it is depleted (returns nil) then closes the destination.

 The solution of the assignment consists of such steps:
 
 StatufulProducer from user to followers -> followers Queue -> StatefulMultiProducer from follower(s) to shots -> shots Queue -> StatefulMultiProducer from shot(s) to likers -> collect and count likers
 
 This approach allows to externalize complete state of the system to defined producers and queues. As an additional benefit this state can be easily serialized and restored.
 
 This solution is of proof-of-concept quality and lacks some features which would be requirements for a complete one:
 
* The solution isn't thread-safe
* Actual state save and restore operations aren't implemented
* Tests coverage is sparse
 
## Installation

1. Clone git repository https://github.com/nik-z/dribbble-stats-assignment.git
2. Replace placeholders in the <project root>/resources/token.txt file with the application id and token

## Usage

Execute 

    lein run <dribble user name>

from the root of the project.

## License

Copyright © 2017 Nikolay Zinchenko

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
