/**
 * Unit tests for the TypeScript layer.
 *
 * `react-native` is mapped to a lightweight mock rather than pulling in the
 * real package: these tests exercise the SDK's own logic (scheduling, native
 * delegation, platform guards), not React Native itself, and the mock keeps
 * the suite fast and dependency-free.
 */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>/src'],
  testMatch: ['**/__tests__/**/*.test.ts'],
  moduleNameMapper: {
    '^react-native$': '<rootDir>/src/__tests__/mocks/reactNative.ts',
  },
  collectCoverageFrom: ['src/**/*.ts', '!src/__tests__/**'],
};
