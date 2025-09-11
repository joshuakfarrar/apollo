package me.joshuakfarrar.apollo.auth

trait Hashable[F[_], P] {
  def hash(plain: String): F[P]
  def verify(plain: String, hashed: P): F[Boolean]
}