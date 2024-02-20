# Set Game Implementation

This project implements a simplified version of the game "Set" in Java, focusing on concurrency, synchronization, and testing.



## Implementation Details

- **Concurrency**: Player actions and game flow are managed through threads. Players (both human and non-human) interact with the game through keyboard inputs or simulated key presses.
- **Synchronization**: Java synchronization mechanisms are used to ensure thread safety, especially in managing player actions and dealer operations.
- **Testing**: JUnit tests are implemented to test key components such as Table, Dealer, and Player classes. Mockito may be used for mocking in some tests.

## Running the Game

1. **Building with Maven**: Use `mvn clean compile test` to compile the project. Run the game with `java -cp target/classes bguspl.set.Main`.
2. **Gameplay**: Players aim to find and claim sets of three cards based on color, number, shape, and shading. The dealer manages card dealing, shuffling, and game flow.


## Bonus details:

Implemented the following:
- Fully supported all configuration fields and avoided magic numbers.
- Terminated all threads gracefully and in reverse order of creation.
- Handled the config.turnTimeoutMillis value as specified.
- Ensured threads do not wake up unnecessarily.

### Enjoy!
